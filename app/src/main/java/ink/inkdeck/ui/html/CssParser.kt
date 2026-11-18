package ink.inkdeck.ui.html

/**
 * 极简 CSS 子集：只认卡片里真正影响阅读的属性。
 *
 * 支持：display / font-size / font-weight / font-style / text-decoration / text-align /
 * color / background-color / border(-width/style/color) / padding / margin / line-height /
 * list-style-type。
 * 选择器只支持单个简单选择器（tag、.class、#id、tag.class）与逗号分组；
 * 后代选择器按「最右一段」匹配（够用，但不完美）。
 */
object CssParser {

    data class Rule(
        val tag: String?,
        val id: String?,
        val className: String?,
        val declarations: Map<String, String>,
        val specificity: Int,
        val order: Int,
        /** `::before` / `::after` 只作为分隔线提示，不参与普通声明叠加。 */
        val pseudo: String? = null,
    )

    private val COMMENT = Regex("(?s)/\\*.*?\\*/")

    fun parse(css: String): List<Rule> {
        val cleaned = css.replace(COMMENT, "")
        val rules = mutableListOf<Rule>()
        var order = 0
        Regex("(?s)([^{}]+)\\{([^}]*)\\}").findAll(cleaned).forEach { match ->
            val selectors = match.groupValues[1].trim()
            if (selectors.startsWith("@")) return@forEach
            val declarations = parseDeclarations(match.groupValues[2])
            selectors.split(',').forEach { selector ->
                val simple = selector.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
                if (simple.isEmpty()) return@forEach
                val pseudo = when {
                    simple.contains("::before") -> "before"
                    simple.contains("::after") -> "after"
                    else -> null
                }
                val base = simple.substringBefore("::")
                rules += buildRule(base, declarations, order++, pseudo)
            }
        }
        return rules
    }

    private fun buildRule(
        selector: String,
        declarations: Map<String, String>,
        order: Int,
        pseudo: String? = null,
    ): Rule {
        var tag: String? = null
        var id: String? = null
        var className: String? = null
        val tagPart = selector.substringBefore('.', selector.substringBefore('#'))
        if (tagPart.isNotEmpty() && tagPart != "*") tag = tagPart.lowercase()
        Regex("#([-\\w]+)").find(selector)?.let { id = it.groupValues[1] }
        Regex("\\.([-\\w]+)").find(selector)?.let { className = it.groupValues[1] }
        val specificity = (if (id != null) 100 else 0) + (if (className != null) 10 else 0) +
            (if (tag != null) 1 else 0)
        return Rule(tag, id, className, declarations, specificity, order, pseudo)
    }

    private fun parseDeclarations(body: String): Map<String, String> =
        body.split(';')
            .mapNotNull { part ->
                val colon = part.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val name = part.substring(0, colon).trim().lowercase()
                val value = part.substring(colon + 1).trim().replace(Regex("\\s*!important$"), "")
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()

    /** 元素命中的声明（样式表按优先级/顺序叠加，行内 style 最后覆盖）。 */
    fun declarationsFor(element: HtmlNode.Element, rules: List<Rule>): Map<String, String> {
        val classes = element.attributes["class"]?.split(Regex("\\s+")).orEmpty().toSet()
        val id = element.attributes["id"]
        val matching = rules.filter { rule ->
            rule.pseudo == null &&
                (rule.tag == null || rule.tag == element.tag.lowercase()) &&
                (rule.className == null || classes.contains(rule.className)) &&
                (rule.id == null || rule.id == id)
        }.sortedWith(compareBy({ it.specificity }, { it.order }))

        val merged = mutableMapOf<String, String>()
        matching.forEach { merged.putAll(it.declarations) }
        element.attributes["style"]?.let { merged.putAll(parseDeclarations(it)) }
        return merged
    }

    /**
     * `::before` / `::after` 在卡片模板里几乎只用来画分隔线
     * （`content:""; height:1px; background:linear-gradient(...)`）。
     * 这里把这类规则折算成「块的上/下分隔线」。
     */
    fun pseudoDivider(element: HtmlNode.Element, rules: List<Rule>, pseudo: String): Boolean {
        val classes = element.attributes["class"]?.split(Regex("\\s+")).orEmpty().toSet()
        val id = element.attributes["id"]
        return rules.any { rule ->
            rule.pseudo == pseudo &&
                (rule.tag == null || rule.tag == element.tag.lowercase()) &&
                (rule.className == null || classes.contains(rule.className)) &&
                (rule.id == null || rule.id == id) &&
                (rule.declarations.containsKey("height") ||
                    rule.declarations.containsKey("background") ||
                    rule.declarations.containsKey("border") ||
                    rule.declarations.containsKey("border-top") ||
                    rule.declarations.containsKey("border-bottom"))
        }
    }

    fun inlineStyle(
        declarations: Map<String, String>,
        tag: String,
        parent: InlineStyle,
        parentSizeSp: Float,
        scale: Float = 1f,
    ): InlineStyle {
        var bold = parent.bold
        var italic = parent.italic
        var underline = parent.underline
        var strike = parent.strike

        when (tag.lowercase()) {
            "b", "strong" -> bold = true
            "i", "em" -> italic = true
            "u", "ins" -> underline = true
            "s", "strike", "del" -> strike = true
        }

        declarations["font-weight"]?.let { weight ->
            when {
                weight == "bold" || weight == "bolder" -> bold = true
                weight.toIntOrNull()?.let { it >= 600 } == true -> bold = true
                weight == "normal" || weight == "400" -> bold = false
            }
        }
        declarations["font-style"]?.let { style ->
            when (style) {
                "italic", "oblique" -> italic = true
                "normal" -> italic = false
            }
        }
        declarations["text-decoration"]?.let { decoration ->
            if (decoration.contains("underline")) underline = true
            if (decoration.contains("line-through")) strike = true
            if (decoration == "none") {
                underline = false
                strike = false
            }
        }

        // 字号与颜色是继承属性：没写就沿用父级，否则 .card 上的 font-size/color 到子元素就丢了。
        val size = declarations["font-size"]?.let { fontSizeToSp(it, parentSizeSp, scale) }
            ?: parent.sizeSp
        val color = declarations["color"] ?: parent.color
        return InlineStyle(bold, italic, underline, strike, size, color)
    }

    fun blockStyle(
        declarations: Map<String, String>,
        parentAlign: HtmlAlign,
        parentSizeSp: Float,
        scale: Float = 1f,
    ): BlockStyle {
        val border = declarations["border"]?.let { parseBorder(it, scale) }
        val borderWidth = border?.first
            ?: declarations["border-width"]?.let { lengthPx(it, parentSizeSp, scale) }
            ?: 0f
        val borderStyle = declarations["border-style"] ?: border?.second ?: "solid"
        val borderColor = border?.third ?: declarations["border-color"]
        val visibleBorder = if (borderStyle == "none" || borderStyle == "hidden") 0f else borderWidth

        return BlockStyle(
            borderColor = if (visibleBorder > 0f) borderColor ?: "#000000" else null,
            borderWidth = visibleBorder,
            padding = declarations["padding"]?.let { lengthPx(it, parentSizeSp, scale) } ?: 0f,
            marginTop = declarations["margin"]?.let { lengthPx(it, parentSizeSp, scale) }
                ?: declarations["margin-top"]?.let { lengthPx(it, parentSizeSp, scale) } ?: 0f,
            marginBottom = declarations["margin"]?.let { lengthPx(it, parentSizeSp, scale) }
                ?: declarations["margin-bottom"]?.let { lengthPx(it, parentSizeSp, scale) } ?: 0f,
            align = when (declarations["text-align"]) {
                "center" -> HtmlAlign.CENTER
                "right" -> HtmlAlign.END
                else -> parentAlign
            },
            listStyle = declarations["list-style-type"],
            lineHeight = declarations["line-height"]?.toFloatOrNull(),
        )
    }

    /** `1px solid #ccc` → (1f, "solid", "#ccc") */
    private fun parseBorder(value: String, scale: Float): Triple<Float, String, String?> {
        val parts = value.trim().split(Regex("\\s+"))
        var width = 0f
        var style = "solid"
        var color: String? = null
        parts.forEach { part ->
            when {
                part.endsWith("px") || part.endsWith("pt") ->
                    width = lengthPx(part, 18f, scale)
                part in setOf("solid", "dashed", "dotted", "none", "double") -> style = part
                part.startsWith("#") || part.startsWith("rgb") -> color = part
            }
        }
        if (style == "none") width = 0f
        return Triple(width, style, color)
    }

    fun lengthPx(value: String, parentSizeSp: Float, scale: Float = 1f): Float {
        val trimmed = value.trim()
        return when {
            trimmed.endsWith("px") ->
                (trimmed.dropLast(2).toFloatOrNull() ?: 0f) * scale
            trimmed.endsWith("pt") ->
                (trimmed.dropLast(2).toFloatOrNull() ?: 0f) * 1.333f * scale
            trimmed.endsWith("em") -> (trimmed.dropLast(2).toFloatOrNull() ?: 0f) * parentSizeSp
            trimmed.endsWith("%") ->
                (trimmed.dropLast(1).toFloatOrNull() ?: 0f) / 100f * parentSizeSp
            else -> trimmed.toFloatOrNull() ?: 0f
        }
    }

    private fun fontSizeToSp(value: String, parentSizeSp: Float, scale: Float): Float? {
        val trimmed = value.trim()
        val named = mapOf(
            "small" to parentSizeSp * 0.85f,
            "medium" to parentSizeSp,
            "large" to parentSizeSp * 1.2f,
            "x-large" to parentSizeSp * 1.4f,
            "xx-large" to parentSizeSp * 1.6f,
            "smaller" to parentSizeSp * 0.85f,
            "larger" to parentSizeSp * 1.2f,
        )
        named[trimmed]?.let { return it }
        return when {
            trimmed.endsWith("px") ->
                (trimmed.dropLast(2).toFloatOrNull() ?: 0f) * scale
            trimmed.endsWith("pt") ->
                (trimmed.dropLast(2).toFloatOrNull() ?: 0f) * 1.333f * scale
            trimmed.endsWith("em") -> (trimmed.dropLast(2).toFloatOrNull() ?: 0f) * parentSizeSp
            trimmed.endsWith("%") ->
                (trimmed.dropLast(1).toFloatOrNull() ?: 0f) / 100f * parentSizeSp
            trimmed.endsWith("rem") -> (trimmed.dropLast(3).toFloatOrNull() ?: 0f) * 18f * scale
            else -> trimmed.toFloatOrNull()
        }?.coerceIn(10f, 80f)
    }
}
