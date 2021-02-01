/*
 * Copyright 2013-2018 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.naf.reactor;

import com.grey.base.utils.ByteArrayRef;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.IP;
import com.grey.base.utils.TimeOps;
import com.grey.naf.ApplicationContextNAF;

// This tests the UDP mode of IOExecReader
public class UDPReaderTest
{
	private static final String rootdir = DispatcherTest.initPaths(UDPReaderTest.class);
	static final String[] iomessages = new String[]{"Message 1", "Message 2", "Here is another message", "The final message"};
	private static final ApplicationContextNAF appctx = ApplicationContextNAF.create("UDPReaderTest");

	@org.junit.Test
	public void testDirect() throws java.io.IOException
	{
		runtest(true);
	}

	@org.junit.Test
	public void testHeap() throws java.io.IOException
	{
		runtest(false);
	}

	private void runtest(boolean directbufs) throws java.io.IOException
	{
		FileOps.deleteDirectory(rootdir);

		// set up Dispatcher
		com.grey.naf.DispatcherDef def = new com.grey.naf.DispatcherDef();
		def.surviveHandlers = false;
		Dispatcher dsptch = Dispatcher.create(appctx, def, com.grey.logging.Factory.getLogger("no-such-logger"));

		// set up UDP reader
		Reader rdr = new Reader(dsptch, directbufs);

		// queue up incoming messages on the UDP reader
		java.net.DatagramSocket wsock = new java.net.DatagramSocket();
		java.net.DatagramPacket pkt = new java.net.DatagramPacket(new byte[0], 0, rdr.getLocalIP(), rdr.getLocalPort());
		wsock.send(pkt); //should not get sent
		for (int idx = 0; idx != iomessages.length; idx++) {
			pkt.setData(iomessages[idx].getBytes());
			wsock.send(pkt);
		}
		rdr.senderPort = wsock.getLocalPort();
		wsock.close();

		// launch
		dsptch.start();
		Dispatcher.STOPSTATUS stopsts = dsptch.waitStopped(TimeOps.MSECS_PER_SECOND * 10, true);
		org.junit.Assert.assertEquals(Dispatcher.STOPSTATUS.STOPPED, stopsts);
		org.junit.Assert.assertTrue(dsptch.completedOK());
		org.junit.Assert.assertTrue(rdr.completed);
		org.junit.Assert.assertFalse(rdr.disc_flag);
	}


	private static class Reader
		extends CM_UDP
	{
		private final java.nio.channels.DatagramChannel udpchan;
		private int msgcnt;
		public boolean completed;
		public boolean disc_flag;
		public int senderPort;

		public Reader(Dispatcher d, boolean directbufs) throws java.io.IOException
		{
			super(d, new com.grey.naf.BufferSpec(1024, 0, directbufs));
	        byte[] ipbytes = IP.ip2net(IP.IP_LOCALHOST, null, 0);
	        java.net.InetAddress ipaddr_localhost = java.net.InetAddress.getByAddress(ipbytes);

			udpchan = java.nio.channels.DatagramChannel.open();
			java.net.DatagramSocket sock = udpchan.socket();
	        java.net.InetSocketAddress saddr = new java.net.InetSocketAddress(ipaddr_localhost, 0);
			sock.bind(saddr);

			registerConnectionlessChannel(udpchan, true);
			getReader().receive();
		}

		@Override
		public void ioReceived(ByteArrayRef data, java.net.InetSocketAddress remaddr) throws java.io.IOException
		{
			String msg = iomessages[msgcnt++];
			org.junit.Assert.assertEquals(msg.length(), data.size());
			org.junit.Assert.assertEquals(msg, new String(data.buffer(), data.offset(), data.size()));
			org.junit.Assert.assertEquals(IP.IP_LOCALHOST, IP.convertIP(remaddr.getAddress()));
			org.junit.Assert.assertEquals(senderPort, remaddr.getPort());
			getReader().endReceive();
			getReader().receive();
			if (msgcnt == iomessages.length) {
				disconnect();
				getDispatcher().stop();
				completed = true;
			}
		}

		@Override
		protected void ioDisconnected(CharSequence diagnostic) {
			disc_flag = true;
		}
	}
}