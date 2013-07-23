/*
 * Copyright 2011-2012 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.logging;

import java.util.logging.Level;

// This class provides much the same functionality as the JUL logger combined with the JUL's FileHandler and ConsoleHandler.
//
// This class is an alternative to the JUL logger, which is polymorphically compatible with it, but implements the basic methods more
// efficiently, thanks to not creating a LogRecord object on every call, or generating any garbage generally.
// Users who stick to the basic methods will benefit from better performance, while those who don't will be no worse off than with the base
// JUL logger, as the more arcane log(), logp() and logrb() variants will pass straight through to the base JUL logger, which will then call
// our log(LogRecord) method.
// The convenience methods such as Logger.info() will get routed to our more efficient replacements rather than log(LogRecord).
// Anything to with the Handler, Filter or ResourceBundle classes will become effectively a no-op (but in practice a wasted op), as we allow the
// calls to pass through.
// This class is constructed with a null parent, and parent operations don't really make sense for us, but we don't intercept them.
//
// This class is also an alternative to JUL_Handler, as it doesn't have any dependency on the GreyBase Logger class, and furthermore this is a
// complete JUL Logger anyway, not a mere handler.
// It is quick and dirty logger however, with no rotation or any other config.
public class AltJUL
	extends java.util.logging.Logger
{
	private int minlvl;  // JUL logger uses volatile levelValue, so cache our own
	private java.io.PrintStream strm;
	private java.util.Calendar dtcal;
	private StringBuilder logbuf;
	private StringBuilder msgbuf;

	@Override
	public void log(Level lvl, String msg) {log(lvl, (CharSequence)msg);}
	@Override
	public void log(Level lvl, String msg, Throwable ex) {log(lvl, msg, ex, true);}
	@Override
    public boolean isLoggable(Level lvl) {return lvl.intValue() >= minlvl;}

	public AltJUL(String pthnam) throws java.io.FileNotFoundException
	{
		this(new java.io.PrintStream(new java.io.FileOutputStream(pthnam, true)));  // because PrintStream(pthnam) doesn't append
	}

	public AltJUL(java.io.PrintStream strm)
	{
		super("greybase.anon", (String)null);
		this.strm = strm;
		dtcal = com.grey.base.utils.TimeOps.getCalendar(null);
		logbuf = new StringBuilder();
		msgbuf = new StringBuilder();
		setLevel(Level.INFO);
	}

	// We intercept the setLevel() call to cache the level locally for efficiency, but then pass it on down so that the base getLevel()
	// method continues to work.
	@Override
	public void setLevel(Level newlvl)
	{
		minlvl = newlvl.intValue();
		super.setLevel(newlvl);
	}
	// Note that LogRecord.getThreadID() does not return the actual JVM thread's ID, but merely a unique per-thread generated by JUL.
	// Then again, Thread.getId() effectively does no different, but at least it provides a common handle on the thread across all
	// subsystems.
	@Override
	public void log(java.util.logging.LogRecord rec)
	{
		if (rec.getLevel().intValue() < minlvl) return;
		msgbuf.setLength(0);
		msgbuf.append("[JT").append(rec.getThreadID()).append("/SEQ=").append(rec.getSequenceNumber());
		msgbuf.append(' ').append(rec.getSourceClassName()).append(':').append(rec.getSourceMethodName()).append("] ");
		msgbuf.append(rec.getMessage());
		Object[] params = rec.getParameters();

		if (params != null && params.length != 0)
		{
			msgbuf.append(" - PARAMS=").append(params.length).append(':');
			for (int idx = 0; idx != params.length; idx++)
			{
				msgbuf.append(com.grey.base.config.SysProps.EOL).append("- ").append(params[idx]);
			}
		}
		log(rec.getLevel(), msgbuf, rec.getThrown(), true);
	}

	public void log(Level lvl, CharSequence msg)
	{
		if (lvl.intValue() < minlvl) return;
		log(msg, lvl);
	}

	public void log(Level lvl, CharSequence msg, Throwable ex, boolean withstack)
	{
		if (ex == null)
		{
			log(lvl, msg);
			return;
		}
		if (lvl.intValue() < minlvl) return;
		log(msg, ex, withstack, lvl);
	}

	// Level has already been approved, and is only being passed in here so we can log it
	private void log(CharSequence msg, Level lvl)
	{
		long systime = System.currentTimeMillis();
		dtcal.setTimeInMillis(systime);
		logbuf.setLength(0);
		com.grey.base.utils.TimeOps.makeTimeLogger(dtcal, logbuf, true, true);
		logbuf.append(' ');
		logbuf.append('T').append(Thread.currentThread().getId()).append(' ');
		logbuf.append(lvl).append(' ').append(msg);
		strm.println(logbuf);
		strm.flush();
	}

	private void log(CharSequence msg, Throwable ex, boolean withstack, Level lvl)
	{
		msgbuf.setLength(0);
		msgbuf.append(msg).append(com.grey.base.GreyException.summary(ex, withstack));
		log(msgbuf, lvl);
	}

	// convenience method
	public void dumpStack(String msg)
	{
		Exception ex = new Exception("Dumping stack using dummy exception");
		log(Level.INFO, msg, ex);
	}
}
