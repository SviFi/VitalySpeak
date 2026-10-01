package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class TranscriberClientTest {

    @Test fun `parses success response`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `parses error response`() {
        val r = TranscriberClient.parseResponse("""{"error":{"message":"Invalid key","type":"auth"}}""")
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }

    @Test fun `handles unknown format`() {
        val r = TranscriberClient.parseResponse("""{"foo":"bar"}""")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `handles malformed json`() {
        val r = TranscriberClient.parseResponse("not json")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `parses verbose json words first, then segments`() {
        val (t, u) = TranscriberClient.parseVerbose("""{"text":" Hi there","segments":[{"start":0,"end":1.2,"text":" Hi there"}],
            "words":[{"word":"Hi","start":0.0,"end":0.4},{"word":"there","start":0.5,"end":1.2}]}""")
        assertEquals("Hi there", t)
        assertEquals(listOf("Hi", "there"), u!!.map { it.text })
        val (_, seg) = TranscriberClient.parseVerbose("""{"text":"a","segments":[{"start":1,"end":2,"text":" a "}]}""")
        assertEquals(1.0, seg!![0].start, 0.0)
        assertEquals("a", seg[0].text)
    }
}
