/*
 * Copyright 2010-2021 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.naf.reactor;

import com.grey.naf.EntityReaper;
import com.grey.naf.reactor.config.ListenerConfig;
import com.grey.naf.reactor.config.SSLConfig;

public abstract class CM_Listener
	extends ChannelMonitor
	implements DispatcherRunnable, EntityReaper
{
	/**
	 * In addition to providing the explicit interface methods, factory classes that are created via naf.xml config
	 * must also provide a constructor with this signature:<br>
	 * <code>classname(com.grey.naf.reactor.CM_Listener listener, Object config)</code>
	 */
	public interface ServerFactory {
		CM_Server createServer();
		default void shutdownServerFactory() {}
	}

	public interface Reporter {
		enum EVENT {STARTED, STOPPED}
		void listenerNotification(EVENT evt, CM_Server s);
	}

	private final String name;
	private final Object controller; //provides context on the application behind the listener - for server-specific use
	private final SSLConfig sslconfig;
	private final java.net.InetAddress srvip;
	private final int srvport;
	private final ServerFactory serverFactory;

	private Reporter reporter;
	private boolean inShutdown;
	private boolean has_stopped;

	@Override
	public String getName() {return name;}

	public int getPort() {return srvport;}
	public java.net.InetAddress getIP() {return srvip;}
	public Object getController() {return controller;}
	public SSLConfig getSSLConfig() {return sslconfig;}
	public ServerFactory getServerFactory() {return serverFactory;}
	protected Reporter getReporter() {return reporter;}
	protected void setReporter(Reporter r) {reporter = r;}

	protected boolean stopListener() {return true;}
	protected boolean inShutdown() {return inShutdown;}

	protected CM_Listener(Dispatcher d, Object controller, EntityReaper rpr, ListenerConfig config) throws java.io.IOException {
		super(d);
		this.controller = controller;
		sslconfig = config.getConfigSSL();
		String iface = config.getInterface();
		int port = config.getPort();
		int srvbacklog = config.getBacklog();

		String lname = config.getName();
		if (lname == null) lname = getDispatcher().getName()+":"+port;
		name = lname;

		getLogger().info("Listener="+name+" in Dispatcher="+getDispatcher().getName()+" initialising on interface="+iface+", port="+port
				+" with controller="+controller+", reaper="+rpr+" - ssl="+sslconfig);

		// set up our listening socket
		java.net.InetAddress ipaddr = (iface == null ? null : com.grey.base.utils.IP.getHostByName(iface));
		java.nio.channels.ServerSocketChannel srvchan = java.nio.channels.ServerSocketChannel.open();
		java.net.ServerSocket srvsock = srvchan.socket();
		srvsock.bind(new java.net.InetSocketAddress(ipaddr, port), srvbacklog);
		srvip = srvsock.getInetAddress();
		srvport = srvsock.getLocalPort();

		serverFactory = config.getServerFactoryGenerator().apply(this);

		setReaper(rpr);
		getDispatcher().getApplicationContext().register(this);
		initChannel(srvchan, true);

		getLogger().info("Listener="+name+" bound to "+srvsock.getInetAddress()+":"+srvport+(port==0?"/dynamic":"")
				         +(iface==null ? "" : " on interface="+iface)+" with backlog="+srvbacklog+" - factory="+serverFactory);
	}

	@Override
	public void startDispatcherRunnable() throws java.io.IOException {
		getLogger().info("Listener="+getName()+": Starting up");
		registerChannel();
		enableListen();
	}

	@Override
	public boolean stopDispatcherRunnable() {
		return stop(false);
	}

	protected boolean stop(boolean notify) {
		getLogger().info("Listener="+getName()+": Received Stop request with notify="+notify+" - in-shutdown="+inShutdown+", stopped="+has_stopped);
		if (inShutdown || has_stopped) return has_stopped; //break up possible mutually recursive calling chains
		inShutdown = true;
		disconnect(false, true); //don't want to notify our reaper till stopped()
		boolean done = stopListener();
		if (done) stopped(notify);
		return done;
	}

	protected void stopped(boolean notify) {
		if (has_stopped) return;
		has_stopped = true;
		EntityReaper rpr = getReaper();
		getLogger().info("Listener="+getName()+" has stopped with notify="+notify+" - reaper="+rpr);
		serverFactory.shutdownServerFactory();
		getDispatcher().getApplicationContext().deregister(this);
		if (notify && rpr != null) rpr.entityStopped(this);
		setReaper(null);
	}

	@Override
	public String toString() {
		return super.toString()+" - name="+getName()+" with server-factory="+getServerFactory()+", controller="+getController()
				+" on "+getIP()+":"+getPort()+" - ssl="+getSSLConfig();
	}
}