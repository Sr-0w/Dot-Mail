package app.dot.mail.ui.pgp

import app.dot.mail.data.model.PgpContent

enum class PgpIndicatorKind { ENCRYPTION, SIGNATURE, UNSUPPORTED_ENCRYPTION }

/** Maps only evidence known by MIME parsing or OpenKeychain; list states never imply signature verification. */
internal fun pgpIndicatorKinds(content: PgpContent?, signature: OpenKeychain.Signature?): List<PgpIndicatorKind> {
    if (content == null) return emptyList()
    if (content is PgpContent.Unsupported) return listOf(PgpIndicatorKind.UNSUPPORTED_ENCRYPTION)
    val encrypted = content is PgpContent.Encrypted || content is PgpContent.InlineEncrypted
    val signedContent = content is PgpContent.Signed || content is PgpContent.InlineSigned
    val signedResult = signature != null && signature.verdict != OpenKeychain.Verdict.UNSIGNED
    return buildList {
        if (encrypted) add(PgpIndicatorKind.ENCRYPTION)
        if (signedContent || signedResult) add(PgpIndicatorKind.SIGNATURE)
    }
}
