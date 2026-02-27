package com.wayrion.pdownload

internal data class ParsedCliArg(
    val raw: String,
    val flag: String,
    val value: String?,
)

internal fun cliMissingRequired(flag: String): Nothing =
    error("CLI error: missing required $flag")

internal fun cliUnknownFlag(rawFlag: String): Nothing =
    error("CLI error: unknown flag: $rawFlag")

internal fun cliInvalidValue(flag: String, value: String, expected: String): Nothing =
    error("CLI error: invalid value for $flag: $value (expected: $expected)")

internal fun parseCliArgs(
    args: Array<String>,
    flagsWithoutValue: Set<String> = emptySet(),
    sanitizeFlags: Boolean = true,
): List<ParsedCliArg> {
    val parsed = ArrayList<ParsedCliArg>(args.size)
    var index = 0

    while (index < args.size) {
        val raw = args[index]
        val flag = if (sanitizeFlags) raw.trim().trimEnd('.', ',', ';', ':') else raw

        if (!flag.startsWith("--") && flag !in flagsWithoutValue) {
            error("CLI error: invalid argument: $raw")
        }

        if (flag in flagsWithoutValue) {
            parsed += ParsedCliArg(raw = raw, flag = flag, value = null)
            index += 1
            continue
        }

        if (index + 1 >= args.size) {
            error("CLI error: missing value for $flag")
        }

        parsed += ParsedCliArg(raw = raw, flag = flag, value = args[index + 1])
        index += 2
    }

    return parsed
}
