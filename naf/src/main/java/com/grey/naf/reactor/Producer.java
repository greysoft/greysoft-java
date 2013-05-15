/*
 * Copyright 2011-2013 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.naf.reactor;

import com.grey.logging.Logger.LEVEL;

/*
 * This class allows a Dispatcher to act as a consumer of events generated by an external Producer
 * which may well be running in a different thread (hence the Dispatcher thread is also known as the
 * Consumer thread).
 * Although this class is called Producer and is owned by the Dispatcher, the actual producer is the
 * external entity who calls its produce() methods. So it is a Producer from the point of view of the
 * Dispatcher (which acts as its consumer) rather than a mechanism by which the Dispatcher acts as a
 * producer.
 */
public final class Producer<T>
{
	public interface Consumer
	{
		void producerIndication(Producer<?> p) throws com.grey.base.FaultException, java.io.IOException;
	}

	public final Dispatcher dsptch;
	public final String consumerType;
	private final com.grey.base.utils.Circulist<T> exchgq;  //MT queue, on which Dispatcher receives items from producer
	private final com.grey.base.utils.Circulist<T> availq;  //non-MT staging queue, only accessed by the Dispatcher
	private final AlertsPipe alertspipe;
	private final com.grey.logging.Logger logger;
	private Consumer consumer;

	public Producer(Class<?> clss, Dispatcher dsptch, Consumer cons)
			throws com.grey.base.ConfigException, com.grey.base.FaultException, java.io.IOException
	{
		this(clss, dsptch, cons, null);
	}

	public Producer(Class<?> clss, Consumer cons, com.grey.logging.Logger log)
			throws com.grey.base.ConfigException, com.grey.base.FaultException, java.io.IOException
	{
		this(clss, null, cons, log);
	}

	private Producer(Class<?> clss_item, Dispatcher d, Consumer cons, com.grey.logging.Logger log)
			throws com.grey.base.ConfigException, com.grey.base.FaultException, java.io.IOException
	{
		dsptch = d;
		logger = (log == null && dsptch != null ? dsptch.logger : log);
		alertspipe = (dsptch == null ? null : new AlertsPipe(dsptch, this));
		consumer = cons;
		consumerType = consumer.getClass().getName()+"/"+clss_item.getName();
		exchgq = new com.grey.base.utils.Circulist<T>(clss_item);
		availq = new com.grey.base.utils.Circulist<T>(clss_item);
		if (logger != null) logger.trace("Dispatcher="+(dsptch==null?"n/a":dsptch.name)+" created Producer="+this
				+" for Consumer="+consumerType);
	}

	// If some items are already on the available queue, then we don't attempt to consume them even if
	// the 'consume' arg is true, as this shutdown could be occurring during a notifyConsumer() callout,
	// in which case the caller has already decided to abort.
	public void shutdown(boolean consume)
	{
		if (consumer == null) return;
		try {
			if (alertspipe != null) alertspipe.shutdown();
		} catch (Exception ex) {
			if (logger != null) logger.log(LEVEL.INFO, ex, true, "Error on Producer shutdown - "+consumerType);
		}
		int ready = availq.size();
		takePendingItems();
		int pending = availq.size() - ready;

		if (logger != null && availq.size() != 0) logger.info("Shutdown Producer="+this+" with pending="+ready+"/"+pending
				+" - Consumer="+consumerType);
		if (consume && ready == 0 && pending != 0) {
			notifyConsumer();
			if (logger != null) logger.info("Shutdown Producer="+this+": Drainage completed - pending="+availq.size());
		}
		consumer = null;
	}

	public T consume()
	{
		if (availq.size() == 0) return null;
		return availq.remove();
	}

	public void produce(T item, Dispatcher d) throws java.io.IOException
	{
		int cnt;
		synchronized (exchgq) {
			cnt = exchgq.size();
			exchgq.append(item);
		}
		produce(d, cnt);
	}

	public void produce(java.util.ArrayList<T> items, Dispatcher d) throws java.io.IOException
	{
		int cnt;
		synchronized (exchgq) {
			cnt = exchgq.size();
			for (int idx = 0; idx != items.size(); idx++) {
				exchgq.append(items.get(idx));
			}
		}
		produce(d, cnt);
	}

	public void produce(T[] items, int off, int len, Dispatcher d) throws java.io.IOException
	{
		int lmt = off + len;
		int cnt;
		synchronized (exchgq) {
			cnt = exchgq.size();
			for (int idx = off; idx != lmt; idx++) {
				exchgq.append(items[idx]);
			}
		}
		produce(d, cnt);
	}

	public void produce(T[] items, Dispatcher d) throws java.io.IOException
	{
		produce(items, 0, items.length, d);
	}

	// This is the final act of the public produce() methods, which are called by the external producer and
	// are the only methods in this class that might be called by a different thread (ie. not the Dispatcher
	// thread).
	// This method is called internally after exchgq has been populated with the new items, and the Dispatcher arg
	// represents the Dispatcher in the context of which this call is being made. If it's the same as the Dispatcher
	// which owns this Producer object, then it is a synchronous call by an in-thread producer, else we have to use
	// the AlertsPipe to signal the owner Dispatcher.
	// If exchgq already had unconsumed items on it, then we assume the owner Dispatcher has already been signalled,
	// so we can skip the I/O cost of sending it a redundant signal.
	private void produce(Dispatcher d, int exchq_prevsize) throws java.io.IOException
	{
		if (consumer == null) {
			throw new java.io.IOException("Illegal put-array on closed Producer+"+this+" - "+(alertspipe==null?"Sync":alertspipe.dsptch.name));
		}
		if (alertspipe == null || d == alertspipe.dsptch) {
			producerEvent(); //we can synchronously call the Consumer
		} else {
			if (exchq_prevsize == 0) alertspipe.signalConsumer();  //one signal is enough
		}
	}

	private void notifyConsumer()
	{
		int ready = availq.size();
		if (consumer == null || ready == 0) return;
		try {
			consumer.producerIndication(this);
		} catch (Exception ex) {
			if (logger != null) logger.log(LEVEL.INFO, ex, true,
					"Consumer failed to handle Producer-indication - left="+availq.size()+"/"+ready+" - "+consumerType);
		}
	}

	private void takePendingItems()
	{
		synchronized (exchgq) {
			while (exchgq.size() != 0) {
				availq.append(exchgq.remove());
			}
		}
	}

	void producerEvent()
	{
		takePendingItems();
		notifyConsumer();
	}


	/*
	 * Encapsulate the ChannelMonitor functionality within a nested class, rather than making it a base class for Producer.
	 * We prefer composition to inheritance, not least because if Producer inherited from ChannelMonitor, it would expose all its methods.
	 * This ChannelMonitor receives I/O indications from the Producer thread(s).
	 * This class is non-private only because Dispatcher.dumpState() needs to be able to see it.
	 */
	static final class AlertsPipe
		extends ChannelMonitor
	{
		public final Producer<?> producer;
		private final java.nio.channels.Pipe.SinkChannel wep;  //Write end-point of pipe
		private final java.nio.ByteBuffer xmtbuf;

		AlertsPipe(Dispatcher d, Producer<?> p) throws com.grey.base.ConfigException, com.grey.base.FaultException, java.io.IOException
		{
			super(d);
			producer = p;

			java.nio.channels.Pipe pipe = java.nio.channels.Pipe.open();
			java.nio.channels.Pipe.SourceChannel rep = pipe.source(); //Read end-point
			wep = pipe.sink();
			wep.configureBlocking(false); //guaranteed not to block in practice

			java.nio.ByteBuffer niobuf = com.grey.base.utils.NIOBuffers.create(1, true);
			niobuf.put((byte)1); //value doesn't matter
			xmtbuf = niobuf.asReadOnlyBuffer();

			// enable event notifications on the read (consumer) endpoint of our pipe
			com.grey.naf.BufferSpec bufspec = new com.grey.naf.BufferSpec(64, 0, false);
			chanreader = new IOExecReader(bufspec);
			initChannel(rep, true, true);
			chanreader.receive(0);
		}

		void shutdown() throws java.io.IOException
		{
			wep.close();
			disconnect();
		}

		// This is called by Producers outside the Dispatcher thread.
		// We don't care if the write() returns zero because it's blocked. We are not sending data which the
		// consumer has to read, but merely kicking it into action, and if the pipe is full, then the
		// consumer will surely be signalled that I/O is pending.
		void signalConsumer() throws java.io.IOException
		{
			xmtbuf.flip();
			wep.write(xmtbuf);
		}

		// This happens within the Dispatcher (consumer) thread.
		@Override
		public void ioReceived(com.grey.base.utils.ArrayRef<byte[]> data) throws com.grey.base.FaultException, java.io.IOException
		{
			producer.producerEvent();
		}
	}
}
