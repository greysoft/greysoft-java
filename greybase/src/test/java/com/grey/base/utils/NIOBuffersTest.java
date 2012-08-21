/*
 * Copyright 2012 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.base.utils;

public class NIOBuffersTest
{
	private static final String ORIGTXT1 = "  Hello NIO  ";
	private static final String ORIGTXT2 = "  And Again  ";

	@org.junit.Test
	public void factory()
	{
		int cap = 1024;
		NIOBuffers.BufferFactory fact = new NIOBuffers.BufferFactory(cap, false);
		java.nio.ByteBuffer buf1 = fact.factory_create();
		org.junit.Assert.assertFalse(buf1.isDirect());
		NIOBuffers.BufferFactory fact2 = new NIOBuffers.BufferFactory(cap, true);
		java.nio.ByteBuffer buf2 = fact2.factory_create();
		org.junit.Assert.assertTrue(buf2.isDirect());

		java.nio.ByteBuffer[] arr = new java.nio.ByteBuffer[]{buf1, buf2};
		for (int idx = 0; idx != arr.length; idx++) {
			org.junit.Assert.assertEquals(cap, arr[idx].capacity());
			org.junit.Assert.assertEquals(cap, arr[idx].limit());
			org.junit.Assert.assertEquals(0, arr[idx].position());
			org.junit.Assert.assertFalse(arr[idx].isReadOnly());
		}
	}

	@org.junit.Test
	public void encode8()
	{
		java.nio.ByteBuffer buf = NIOBuffers.encode(ORIGTXT1, null, null, null, null, true);
		verify(ORIGTXT1, buf);
		java.nio.ByteBuffer buf2 = NIOBuffers.encode(ORIGTXT2, null, null, null, buf, false);
		org.junit.Assert.assertSame(buf, buf2);
		verify(ORIGTXT2, buf);
		buf = NIOBuffers.encode(ORIGTXT1, null, null, null, null, false);
		verify(ORIGTXT1, buf);
		buf2 = NIOBuffers.encode(ORIGTXT2, null, null, null, buf, true);
		org.junit.Assert.assertSame(buf, buf2);
		verify(ORIGTXT2, buf);

		// the text is all 8-bit, so this should also work
		byte[] barr = new byte[ORIGTXT1.length()];
		for (int idx = 0; idx != ORIGTXT1.length(); idx++) barr[idx] = (byte)ORIGTXT1.charAt(idx);
		buf = NIOBuffers.encode(barr, 0, barr.length, null, true);
		org.junit.Assert.assertEquals(0, buf.position());
		org.junit.Assert.assertEquals(barr.length, buf.limit());
		org.junit.Assert.assertEquals(barr.length, buf.capacity());
		StringBuilder sb = NIOBuffers.decode(buf, 0, -1, null, false);
		org.junit.Assert.assertEquals(ORIGTXT1, sb.toString());
		// now repeat into existing buffer
		buf2 = NIOBuffers.encode(barr, 0, barr.length, buf, true);
		org.junit.Assert.assertSame(buf, buf2);
		org.junit.Assert.assertEquals(0, buf.position());
		org.junit.Assert.assertEquals(barr.length, buf.limit());
		org.junit.Assert.assertEquals(barr.length, buf.capacity());
		sb = NIOBuffers.decode(buf, 0, -1, null, false);
		org.junit.Assert.assertEquals(ORIGTXT1, sb.toString());

		// verify that buffers don't auto-grow
		buf = NIOBuffers.create(barr.length - 1, false);
		try {
			NIOBuffers.encode(barr, 0, barr.length, buf, true);
			org.junit.Assert.fail("Heap Buffer was supposed to overflow");
		} catch (java.nio.BufferOverflowException ex) {}
		buf = NIOBuffers.create(barr.length - 1, true);
		try {
			NIOBuffers.encode(barr, 0, barr.length, buf, true);
			org.junit.Assert.fail("Direct Buffer was supposed to overflow");
		} catch (java.nio.BufferOverflowException ex) {}

		// now test deliberate growth (in fact, reallocation)
		buf2 = NIOBuffers.ensureCapacity(buf, barr.length-1, false);
		org.junit.Assert.assertSame(buf, buf2);
		org.junit.Assert.assertEquals(barr.length-1, buf.capacity());
		buf2 = NIOBuffers.ensureCapacity(buf, barr.length, false);
		org.junit.Assert.assertNotSame(buf, buf2);
		org.junit.Assert.assertEquals(barr.length, buf2.capacity());
	}

	@org.junit.Test
	public void encodeCharset()
	{
		java.nio.ByteBuffer buf = NIOBuffers.encode(ORIGTXT1, null, null, "UTF-8", null, false);
		verify(ORIGTXT1, buf);
		java.nio.ByteBuffer buf2 = NIOBuffers.encode(ORIGTXT2, null, null, "UTF-8", buf, false);
		org.junit.Assert.assertSame(buf, buf2);
		verify(ORIGTXT2, buf);

		java.nio.charset.CharsetEncoder chenc = NIOBuffers.getEncoder("UTF-8");
		buf = NIOBuffers.encode(ORIGTXT1, chenc, null, null, null, true);
		verify(ORIGTXT1, buf);
	}

	public void verify(String txt, java.nio.ByteBuffer bybuf)
	{
		org.junit.Assert.assertEquals(0, bybuf.position());
		org.junit.Assert.assertEquals(txt.length(), bybuf.limit());
		java.nio.charset.CharsetDecoder chdec = NIOBuffers.getDecoder("UTF-8");

		StringBuilder sb = NIOBuffers.decode(bybuf, null, chdec);
		org.junit.Assert.assertEquals(txt.length(), bybuf.position());
		org.junit.Assert.assertEquals(txt.length(), bybuf.limit());
		bybuf.flip();
		org.junit.Assert.assertEquals(txt, sb.toString());
		org.junit.Assert.assertEquals(0, bybuf.position());
		org.junit.Assert.assertEquals(txt.length(), bybuf.limit());

		StringBuilder sb2 = NIOBuffers.decode(bybuf, 0, -1, sb, false);
		org.junit.Assert.assertSame(sb, sb2);
		org.junit.Assert.assertEquals(txt+txt, sb.toString());
		org.junit.Assert.assertEquals(0, bybuf.position());
		org.junit.Assert.assertEquals(txt.length(), bybuf.limit());
		
		sb = NIOBuffers.decode(bybuf, 0, -1, null, true);
		org.junit.Assert.assertEquals(txt.trim(), sb.toString());
		org.junit.Assert.assertEquals(0, bybuf.position());
		org.junit.Assert.assertEquals(txt.length(), bybuf.limit());
	}
}
