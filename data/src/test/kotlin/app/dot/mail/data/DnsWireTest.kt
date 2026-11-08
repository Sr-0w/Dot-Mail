package app.dot.mail.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Fixtures are real MX responses captured from a public resolver (2026-11-08). */
class DnsWireTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // snyders.xyz MX → 5 mta-gw.infomaniak.ch (name compressed against the question section).
    private val snyders = hex(
        "12348180000100010000000007736e79646572730378797a00000f0001c00c000f000100000e1000180005066d74612d67770a696e666f6d616e69616b02636800",
    )

    // gmail.com MX: 5 records, deliberately returned out of preference order, exchange names use pointers.
    private val gmail = hex(
        "12348180000100050000000005676d61696c03636f6d00000f0001c00c000f0001000006000020000a04616c74310d676d61696c2d736d74702d696e016c06676f6f676c65c012" +
            "c00c000f000100000600000900" + "1e04616c7433c02e" +
            "c00c000f00010000060000040005c02e" +
            "c00c000f0001000006000009002804616c7434c02e" +
            "c00c000f0001000006000009001404616c7432c02e",
    )

    // NXDOMAIN (RCODE 3) with an SOA in the authority section.
    private val nxdomain = hex(
        "123481830001000000010000116e6f6e6578697374656e742d7a7a2d7171076578616d706c6500000f0001000006000100015180004001610c726f6f742d73657276657273036e657400056e73746c640c766572697369676e2d67727303636f6d0078c3feed000007080000038400093a8000015180",
    )

    @Test fun `parses a single compressed MX`() {
        assertThat(DnsWire.parseMx(snyders)).containsExactly("mta-gw.infomaniak.ch")
    }

    @Test fun `sorts several MX by preference and follows compression pointers`() {
        assertThat(DnsWire.parseMx(gmail)).containsExactly(
            "gmail-smtp-in.l.google.com", // 5
            "alt1.gmail-smtp-in.l.google.com", // 10
            "alt2.gmail-smtp-in.l.google.com", // 20
            "alt3.gmail-smtp-in.l.google.com", // 30
            "alt4.gmail-smtp-in.l.google.com", // 40
        ).inOrder()
    }

    @Test fun `NXDOMAIN gives nothing`() {
        assertThat(DnsWire.parseMx(nxdomain)).isEmpty()
    }

    @Test fun `garbage and truncation never throw`() {
        assertThat(DnsWire.parseMx(ByteArray(0))).isEmpty()
        assertThat(DnsWire.parseMx(ByteArray(5))).isEmpty()
        for (n in 12 until snyders.size) assertThat(DnsWire.parseMx(snyders.copyOf(n))).isEmpty()
        // pointer loop: answer name points at itself
        val loop = snyders.copyOf()
        loop[snyders.size - 22] = 0xC0.toByte(); loop[snyders.size - 21] = (snyders.size - 22).toByte()
        DnsWire.parseMx(loop) // must terminate
    }
}
