/*
 * Copyright 2012-2013 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.naf.reactor;

import com.grey.base.utils.FileOps;
import com.grey.base.utils.TimeOps;

public class ChannelMonitorTest
{
	private static final String rootdir = DispatcherTest.initPaths(ChannelMonitorTest.class);
	protected int cmcnt;
	protected boolean completed;

	private static class CMR extends ChannelMonitor
	{
		private final ChannelMonitorTest runner;
		public final CMW writer;
		private final int xmtmax; //total number of transmits to do
		private int rcvmax; //total number of bytes to receive before we exit
		private int rcvbytes;  //total number of bytes received
		private int xmtcnt;  //number of transmits performed

		public CMR(Dispatcher d, java.nio.channels.SelectableChannel r, java.nio.channels.SelectableChannel w,
						com.grey.naf.BufferSpec bufspec, int xmtmax,
						ChannelMonitorTest runner)
				throws com.grey.base.ConfigException, com.grey.base.FaultException, java.io.IOException {
			super(d);
			this.runner = runner;
			this.xmtmax = xmtmax;
			writer = new CMW(d, w, bufspec, runner);
			chanreader = new IOExecReader(bufspec);
			initChannel(r, true, true);
			chanreader.receive(0);
			runner.cmcnt++;
		}

		@Override
		protected void ioReceived(com.grey.base.utils.ArrayRef<byte[]> rcvdata)
				throws com.grey.base.FaultException, java.io.IOException
		{
			rcvbytes += rcvdata.ar_len;

			if (xmtcnt < xmtmax) {
				xmtcnt++;
				writer.write(rcvdata.ar_buf, rcvdata.ar_off, rcvdata.ar_len);
			} else {
				if (rcvmax == 0) {
					rcvmax = writer.xmtbytes;
					writer.stop();
				}
			}

			if (rcvbytes != 0 && rcvbytes == rcvmax) {
				chanreader.endReceive();
				disconnect();
				disconnect();//make sure twice is safe
				if (--runner.cmcnt == 0) {
					runner.completed = true;
					dsptch.stop();
				}
			}
		}
	}

	private static class CMW extends ChannelMonitor
	{
		private final ChannelMonitorTest runner;
		public int xmtbytes;  //total number of bytes transmitted

		public CMW(Dispatcher d, java.nio.channels.SelectableChannel w, com.grey.naf.BufferSpec bufspec, ChannelMonitorTest runner)
				throws com.grey.base.ConfigException, com.grey.base.FaultException, java.io.IOException {
			super(d);
			this.runner = runner;
			chanwriter = new IOExecWriter(bufspec);
			initChannel(w, true, true);
			runner.cmcnt++;
		}

		public void write(byte[] data, int off, int len) throws java.io.IOException {
			xmtbytes += len;
			chanwriter.transmit(data, off, len);
		}

		public void stop() throws java.io.IOException {
			boolean done = disconnect();
			if (chanwriter.isBlocked()) {
				org.junit.Assert.assertFalse(done);
				return;
			}
			org.junit.Assert.assertTrue(done);
			terminated();
		}

		@Override
		protected void disconnectLingerDone(boolean ok, CharSequence info, Throwable ex) {
			if (!ok) org.junit.Assert.fail("Disconnect failed - "+info+" - "+ex);
			boolean done = disconnect(true); //make sure repeated call is ok
			org.junit.Assert.assertTrue(done);
			terminated();
		}

		private void terminated() {
			boolean done = disconnect(); //make sure twice is safe
			org.junit.Assert.assertTrue(done);

			if (--runner.cmcnt == 0) {
				try {
					dsptch.stop();
				} catch (Exception ex2) {
					org.junit.Assert.fail("Failed to stop Dispatcher - "+ex2);
					return;
				}
				runner.completed = true;
			}
		}

	}

	@org.junit.Test
	public void testDirect() throws com.grey.base.GreyException, java.io.IOException
	{
		launch(true);
	}

	@org.junit.Test
	public void testHeap() throws com.grey.base.GreyException, java.io.IOException
	{
		launch(false);
	}

	private void launch(boolean directbufs) throws com.grey.base.GreyException, java.io.IOException
	{
		FileOps.deleteDirectory(rootdir);
		int entitycnt = 64;
		int xmtmax = 5;  //only the write() calls from reader CM count towards this, not the calls below
		int xmtsiz = 10 * 1024;

		completed = false;
		cmcnt = 0;
		int rcvcap = xmtsiz - 1;
		com.grey.naf.BufferSpec bufspec = new com.grey.naf.BufferSpec(rcvcap, xmtsiz, directbufs);

		com.grey.naf.DispatcherDef def = new com.grey.naf.DispatcherDef();
		def.hasNafman = false;
		def.surviveHandlers = false;
		Dispatcher dsptch = Dispatcher.create(def, null, com.grey.logging.Factory.getLogger("no-such-logger"));
		CMR[] entities = new CMR[entitycnt];
		byte[] xmtbuf = new byte[xmtsiz];
		java.util.Arrays.fill(xmtbuf, (byte)1);

		for (int idx = 0; idx != entities.length; idx++) {
			java.nio.channels.Pipe pipe = java.nio.channels.Pipe.open();
			java.nio.channels.Pipe.SourceChannel rep = pipe.source();
			java.nio.channels.Pipe.SinkChannel wep = pipe.sink();
			CMR cm = new CMR(dsptch, rep, wep, bufspec, xmtmax, this);
			entities[idx] = cm;
			org.junit.Assert.assertTrue(cm.isConnected());
			org.junit.Assert.assertTrue(cm.writer.isConnected());
			// Although a pipe is only 8K, an initial write never blocks, no matter large.
			// So throw in a second write to trigger the IOExecWriter backlog processing,
			// if xmtsiz is meant to be large enough to cause it.
			cm.writer.write(xmtbuf, 0, xmtbuf.length);
			if (xmtbuf.length > 8 * 1024) cm.writer.write(xmtbuf, 0, xmtbuf.length);
		}
		long systime1 = System.currentTimeMillis();
		dsptch.start();
		dsptch.waitStopped();
		long systime2 = System.currentTimeMillis();
		org.junit.Assert.assertTrue(completed);
		System.out.println("BulkTest-"+entitycnt+"/"+xmtmax+"/"+xmtsiz+"/direct="+directbufs+" = "+TimeOps.expandMilliTime(systime2 - systime1));

		for (int idx = 0; idx != entities.length; idx++) {
			CMR cm = entities[idx];
			org.junit.Assert.assertFalse(cm.isConnected());
			org.junit.Assert.assertFalse(cm.writer.isConnected());
			org.junit.Assert.assertEquals(cm.writer.xmtbytes, cm.rcvbytes);
		}
		org.junit.Assert.assertEquals(bufspec.xmtpool.size(), bufspec.xmtpool.population());
	}
}
