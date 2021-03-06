/*
 * Copyright 2010-2021 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.base.utils;

import java.time.Duration;

import com.grey.base.config.SysProps;

public final class TimeOps
{
	public static final String TZDFLT = SysProps.get("grey.timezone");  //null means use JVM system default

	// Calendar DAY_OF_WEEK runs from 1=Sunday to 7=Saturday.
	// This property specifies an alternative week, with the default of 2 meaning that we regard Monday as day 1
	public static final int WDAY1 = SysProps.get("grey.weekday1", 2);

	// these sequences have to start with SUN to align with java.util.Calendar.DAY_OF_WEEK
	public static final String[] shortdays = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
	public static final String[] longdays = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};

	public static final String[] shortmonths = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep",
		"Oct", "Nov", "Dec"};
	public static final String[] longmonths = {"January", "February", "March", "April", "May", "June", "July",
		"August", "September", "October", "November", "December"};

	public static final long MSECS_PER_SECOND = Duration.ofSeconds(1).toMillis();
	public static final long MSECS_PER_MINUTE = Duration.ofMinutes(1).toMillis();
	public static final long MSECS_PER_HOUR = Duration.ofHours(1).toMillis();
	public static final long MSECS_PER_DAY = Duration.ofDays(1).toMillis();

	private static final int MAXTIME32DIGITS = Integer.toString(Integer.MAX_VALUE).length(); //gives max length of Unix 32-bit timestamp
	private static final int MAXTIMEDIGITS = MAXTIME32DIGITS+3; //+3 for the milliseconds precision of the Java timestamp
	private static final long THRESHOLD_TIME = thresholdPaddedTime();

	public static StringBuilder makeTimeRFC822(long systime, StringBuilder buf)
	{
		return makeTimeRFC822(systime, null, buf);
	}

	public static StringBuilder makeTimeRFC822(long systime, String tz, StringBuilder buf)
	{
		java.util.Calendar dtcal = getCalendar(systime, tz);
		return makeTimeRFC822(dtcal, buf);
	}

	public static StringBuilder makeTimeISO8601(long systime, StringBuilder buf, boolean withtime, boolean basicformat, boolean withzone)
	{
		return makeTimeISO8601(systime, null, buf, withtime, basicformat, withzone);
	}

	public static StringBuilder makeTimeISO8601(long systime, String tz, StringBuilder buf, boolean withtime, boolean basicformat, boolean withzone)
	{
		java.util.Calendar dtcal = getCalendar(systime, tz);
		return makeTimeISO8601(dtcal, buf, withtime, basicformat, withzone);
	}

	public static StringBuilder makeTimeLogger(long systime, StringBuilder buf, boolean withdate, boolean withmilli)
	{
		return makeTimeLogger(systime, null, buf, withdate, withmilli);
	}

	public static StringBuilder makeTimeLogger(long systime, String tz, StringBuilder buf, boolean withdate, boolean withmilli)
	{
		java.util.Calendar dtcal = getCalendar(systime, tz);
		return makeTimeLogger(dtcal, buf, withdate, withmilli);
	}

	// RFC-822 only allows some very specific timezone abbreviations (GMT, various US locales defined by
	// ANSI and the 1-letter military designators), so we don't bother with that and just use the differential
	// notation instead - see RFC-822 section 5.1.
	// Note also that day-of-month does not have to be padded to 2 digits, but we do so just to be on the
	// safe side as no parser is going to object to that.
	// The short-day-name also seems to be optional, but I've never seen an email Date field without oone
	@SuppressWarnings("static-access")
	public static StringBuilder makeTimeRFC822(java.util.Calendar dtcal, StringBuilder buf)
	{
		if (buf == null) buf = new StringBuilder();
		buf.append(shortdays[dtcal.get(dtcal.DAY_OF_WEEK)-1]).append(", ");
		StringOps.zeroPad(buf, dtcal.get(dtcal.DAY_OF_MONTH), 2).append(' ');
		buf.append(shortmonths[dtcal.get(dtcal.MONTH)]).append(' ');
		buf.append(dtcal.get(dtcal.YEAR)).append(' ');
		StringOps.zeroPad(buf, dtcal.get(dtcal.HOUR_OF_DAY), 2).append(':');
		StringOps.zeroPad(buf, dtcal.get(dtcal.MINUTE), 2).append(':');
		StringOps.zeroPad(buf, dtcal.get(dtcal.SECOND), 2);
		buf.append(' ');
		return withDiffZone(dtcal, buf);
	}

	@SuppressWarnings("static-access")
	public static StringBuilder makeTimeISO8601(java.util.Calendar dtcal, StringBuilder buf, boolean withtime, boolean basicformat, boolean withzone)
	{
		if (buf == null) buf = new StringBuilder();
		String dlmdate = "-";
		String dlmtime = ":";
	
		if (basicformat) {
			dlmdate = "";
			dlmtime = "";
		}
		buf.append(dtcal.get(dtcal.YEAR)).append(dlmdate);  // always 4 digits anyway, so no need for zeropad()
		StringOps.zeroPad(buf, dtcal.get(dtcal.MONTH) + 1, 2).append(dlmdate);
		StringOps.zeroPad(buf, dtcal.get(dtcal.DAY_OF_MONTH), 2);
		
		if (withtime) {
			long gmtoff = (dtcal.get(dtcal.ZONE_OFFSET) + dtcal.get(dtcal.DST_OFFSET)) / MSECS_PER_HOUR;
			char zonesign = '+';
			
			if (gmtoff == 0) {
				zonesign = 'Z';
			} else if (gmtoff < 0) {
				zonesign = '-';
				gmtoff *= -1;
			}
			buf.append('T');
			StringOps.zeroPad(buf, dtcal.get(dtcal.HOUR_OF_DAY), 2).append(dlmtime);
			StringOps.zeroPad(buf, dtcal.get(dtcal.MINUTE), 2).append(dlmtime);
			StringOps.zeroPad(buf, dtcal.get(dtcal.SECOND), 2);
			
			if (withzone) {
				buf.append(zonesign);
				if (zonesign != 'Z') StringOps.zeroPad(buf, (int)gmtoff, 2);
			}
		}
		return buf;
	}

	@SuppressWarnings("static-access")
	public static StringBuilder makeTimeLogger(java.util.Calendar dtcal, StringBuilder buf, boolean withdate, boolean withmilli)
	{
		if (buf == null) buf = new StringBuilder();
		if (withdate) {
			buf.append(dtcal.get(dtcal.YEAR)).append('-');  // always 4 digits anyway, so no need for zeropad()
			StringOps.zeroPad(buf, dtcal.get(dtcal.MONTH) + 1, 2).append('-');
			StringOps.zeroPad(buf, dtcal.get(dtcal.DAY_OF_MONTH), 2).append(' ');
		}
		StringOps.zeroPad(buf, dtcal.get(dtcal.HOUR_OF_DAY), 2).append(':');
		StringOps.zeroPad(buf, dtcal.get(dtcal.MINUTE), 2).append(':');
		StringOps.zeroPad(buf, dtcal.get(dtcal.SECOND), 2);

		if (withmilli) {
			buf.append('.');
			StringOps.zeroPad(buf, dtcal.get(dtcal.MILLISECOND), 3);
		}
		return buf;
	}

	//appends the timezone in differential form
	public static StringBuilder withDiffZone(java.util.Calendar dtcal, StringBuilder buf)
	{
		long gmtoff = dtcal.getTimeZone().getOffset(dtcal.getTimeInMillis()) / MSECS_PER_MINUTE;
		long gmtdist = Math.abs(gmtoff);
		char zonesign = (gmtoff == gmtdist ? '+' : '-');
		buf.append(zonesign);
		StringOps.zeroPad(buf, (int)(gmtdist / 60), 2);  //whole hours
		StringOps.zeroPad(buf, (int)(gmtdist % 60), 2);  //minutes - not all zones are 60 minutes apart
		return buf;
	}

	// The year is 4 digits and months are numbered from 0, days from 1
	public static long getSystime(java.util.Calendar dtcal, int yy, int mm, int dd, int hh, int mins)
	{
		if (dtcal == null) dtcal = getCalendar(null);
		dtcal.setLenient(false);
		dtcal.set(java.util.Calendar.YEAR, yy);
		dtcal.set(java.util.Calendar.MONTH, mm);
		dtcal.set(java.util.Calendar.DAY_OF_MONTH, dd);
		dtcal.set(java.util.Calendar.HOUR_OF_DAY, hh);
		dtcal.set(java.util.Calendar.MINUTE, mins);
		dtcal.set(java.util.Calendar.SECOND, 0);
		dtcal.set(java.util.Calendar.MILLISECOND, 0);
		return dtcal.getTimeInMillis();
	}

	public static long parseMilliTime(CharSequence str)
	{
		return parseMilliTime(str, 0, str.length());
	}

	public static long parseMilliTime(CharSequence str, int off, int len)
	{
		long msecs= 0;
		int lmt = off + len;
		int off_unit = 0; //offset at which current unit begins
		long prevmult = MSECS_PER_DAY + 1L;

		for (int idx = off; idx != lmt; idx++) {
			char ch = str.charAt(idx);
			int len_unit = idx - off_unit;
			int len_symbol = 1;
			long mult;

			if (ch == 'd') {
				mult = MSECS_PER_DAY;
			} else if (ch == 'h') {
				mult = MSECS_PER_HOUR;
			} else if (ch == 'm') {
				if (idx != lmt - 1 && str.charAt(idx+1) == 's') {
					mult = 1;
					len_symbol = 2;
				} else {
					mult = MSECS_PER_MINUTE;
				}
			} else if (ch == 's') {
				mult = MSECS_PER_SECOND;
			} else {
				if (!Character.isDigit(ch)) {
					throw new NumberFormatException("Invalid char="+ch+" at pos="+(idx-off+1)+"- "+str.subSequence(off, lmt));
				}
				if (idx != lmt - 1) continue;
				//this sequence ends in a digit, so treat as if followed by "ms"
				mult = 1;
				len_unit++;
			}

			if (prevmult <= mult) {
				throw new NumberFormatException("Time units repeated or in wrong sequence - "+str.subSequence(off, lmt));
			}
			// allow sloppy syntax where symbols are adjacent - interpret as if a zero between them
			long unit = (len_unit == 0 ? 0 : IntValue.parseDecimal(str, off_unit, len_unit));
			msecs += (unit * mult);
			off_unit = idx + len_symbol;
			if (len_symbol != 1) idx += len_symbol - 1;
			prevmult = mult;
		}
		return msecs;
	}

	public static StringBuilder expandMilliTime(long msecs)
	{
		return expandMilliTime(msecs, null, false);
	}

	public static StringBuilder expandMilliTime(long msecs, StringBuilder str, boolean reset)
	{
		return expandMilliTime(msecs, str, reset, "");
	}

	public static StringBuilder expandMilliTime(long msecs, StringBuilder str, boolean reset, String dlm_units)
	{
		if (str == null) {
			str = new StringBuilder();
		} else if (reset) {
			str.setLength(0);
		}
		int origlen = str.length();
		String dlm = "";

		if (msecs < 0) {
			msecs = Math.abs(msecs);
			str.append("minus-");
		}

		long units = msecs / MSECS_PER_DAY;
		if (units != 0) {
			str.append(dlm).append(units).append('d');
			msecs = msecs % MSECS_PER_DAY;
			dlm = dlm_units;
		}

		units = msecs / MSECS_PER_HOUR;
		if (units != 0) {
			str.append(dlm).append(units).append('h');
			msecs = msecs % MSECS_PER_HOUR;
			dlm = dlm_units;
		}

		units = msecs / MSECS_PER_MINUTE;
		if (units != 0) {
			str.append(dlm).append(units).append('m');
			msecs = msecs % MSECS_PER_MINUTE;
			dlm = dlm_units;
		}

		units = msecs / MSECS_PER_SECOND;
		if (units != 0) {
			str.append(dlm).append(units).append('s');
			msecs = msecs % MSECS_PER_SECOND;
			dlm = dlm_units;
		}

		if (msecs != 0 || str.length() == origlen) {
			str.append(dlm).append(msecs).append("ms");
		}
		return str;
	}

	public static java.util.Calendar getCalendar(long systime, String tz)
	{
		java.util.Calendar cal = getCalendar(tz);
		cal.setTimeInMillis(systime);
		return cal;
	}

	public static java.util.Calendar getCalendar(String tz)
	{
		if (tz == null) tz = TZDFLT;
		if (tz == null) return java.util.Calendar.getInstance();
		return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(tz));
	}

	public static String displayTimezone(java.util.Calendar cal)
	{
		return displayTimezone(cal.getTimeZone(), cal.getTimeInMillis());
	}

	public static String displayTimezone(java.util.TimeZone tz, long systime)
	{
		StringBuilder sb = new StringBuilder(96);
		int off = tz.getOffset(systime);
		java.util.Date dt = new java.util.Date(systime);
		sb.append("TZ=").append(tz.getID());
		if (off != 0) sb.append('/').append(off > 0 ? "+" : "").append(expandMilliTime(off));
		sb.append(" (").append(tz.getDisplayName());
		if (tz.inDaylightTime(dt)) sb.append(" - ").append(tz.getDisplayName(true, java.util.TimeZone.LONG));
		sb.append(')');
		return sb.toString();
	}

	public static final StringBuilder zeroPad(long systime, StringBuilder sb)
	{
		if (systime < THRESHOLD_TIME) {
			int len = sb.length();
			sb.append(systime);
			int pad = MAXTIMEDIGITS - (sb.length() - len);
			sb.setLength(len);
			for (int loop = 0; loop != pad; loop++) sb.append('0');
			sb.append(systime);
		} else {
			sb.append(systime);
		}
		return sb;
	}

	// Determine the minimum time which requires the max number of Unix-time digits to represent it.
	// From Sep 9th 2001 till Jan 19th 2038 (when 32-bit Unix seconds-time wraps around) the Java milliseconds
	// time will be a constant 13 digits in length, and so will not need zero padding.
	// In fact, Java time won't exceed 13 digits till the year 2286!
	private static long thresholdPaddedTime()
	{
		StringBuilder sb = new StringBuilder();
		for (int loop = 0; loop < MAXTIMEDIGITS - 1; loop++) sb.append('9');
		long systime = Long.valueOf(sb.toString()).longValue();
		return systime + 1;
	}
}