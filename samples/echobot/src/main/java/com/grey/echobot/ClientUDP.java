/*
 * Copyright 2012-2021 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.echobot;

import com.grey.base.utils.ByteArrayRef;

public class ClientUDP
	extends com.grey.naf.reactor.CM_UDP
{
	private final String name;
	private final java.nio.ByteBuffer niobuf;
	private final ClientGroup grp;
	private final String logpfx;

	private long time_start; //time at which this client started
	private int msgnum; //current message - 1 means on first message
	private int msgbytes; //number of bytes of current message echoed back so far
	private long time_xmit; //time at which current message was sent

	@Override
	public String getName() {return name;}

	public ClientUDP(String name, int id, ClientGroup g, com.grey.naf.BufferGenerator bufspec, byte[] msgbuf, int sockbufsiz)
			throws java.io.IOException
	{
		super(g.dsptch, null, bufspec, sockbufsiz);
		this.name = name;
		grp = g;
		logpfx = "Client "+getDispatcher().getName()+"/"+id+": ";
		niobuf = com.grey.base.utils.NIOBuffers.encode(msgbuf, 0, msgbuf.length, null, bufspec.directbufs);
	}

	// This is called in the Dispatcher thread
	@Override
	public void startDispatcherRunnable() throws java.io.IOException {
		super.startDispatcherRunnable();
		time_start = System.nanoTime();
		transmit();
	}

	@Override
	public void ioDisconnected(CharSequence diag) {
		getLogger().info(logpfx+" Unsolicited disconnect - msgnum="+msgnum+"/"+grp.msgcnt+", msgbytes="+msgbytes+"/"+grp.echosize);
		disconnect();
		try {
			completed(false);
		} catch (Exception ex) {
			getLogger().error(logpfx+" Failed to signal ClientGroup - "+com.grey.base.ExceptionUtils.summary(ex));
		}
	}

	@Override
	public void ioReceived(ByteArrayRef data, java.net.InetSocketAddress remaddr) throws java.io.IOException {
		if (grp.verify) {
			for (int idx = 0; idx != data.size(); idx++) {
				byte rcv = (byte)data.byteAt(idx);
				byte exp = niobuf.get(msgbytes + idx);
				if (rcv != exp) {
					getLogger().info(logpfx+" Invalid reply@"+idx+"="+rcv+" vs "+exp
							+" - msgnum="+msgnum+"/"+grp.msgcnt+", msgbytes="+msgbytes+"/"+grp.echosize);
					completed(false);
					return;
				}
			}
		}
		msgbytes += data.size();
		if (msgbytes != grp.echosize) return;
		grp.latencies.add(System.nanoTime() - time_xmit);

		// the message we sent has now been echoed back in full
		if (msgnum == grp.msgcnt) {
			// and we've sent the full complement of messages
			completed(true);
			return;
		}
		transmit();
	}

	private void transmit() throws java.io.IOException {
		msgnum++;
		msgbytes = 0;
		niobuf.position(0);
		niobuf.limit(grp.echosize);
		time_xmit = System.nanoTime();
		int nbytes = transmit(niobuf, grp.tsap.sockaddr);

		if (nbytes != grp.echosize) {
			getLogger().error(logpfx+" Send failed - nbytes="+nbytes+"/"+grp.echosize);
			completed(false);
		}
	}

	private void completed(boolean success) {
		disconnect();
		grp.terminated(success, System.nanoTime() - time_start);
	}
}