/*
 * Copyright 2013 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.naf.nafman;

import com.grey.base.utils.ByteOps;

/*
 * HTTP 1.0: RFC-1945 (May 1996)
 * HTTP 1.1: RFC-2616 (Jun 1999) - finalised as RFC-2068 (Jan 1997, aka HTTP-NG)
 */
final class HTTP
{
	public static final String METHOD_GET = "GET";
	public static final String METHOD_POST = "POST";
	public static final String METHOD_HEAD = "HEAD";

	public static final String HDR_CLEN = "Content-Length";
	public static final String HDR_CTYPE = "Content-Type";
	private static final String HDR_CNX = "Connection";
	private static final String HDR_CACHECTL = "Cache-Control";

	public static final String CTYPE_TEXT = "text/plain";
	public static final String CTYPE_XML = "application/xml";
	public static final String CTYPE_HTML = "text/html";
	public static final String CTYPE_CSS = "text/css";
	public static final String CTYPE_PNG = "image/png";
	public static final String CTYPE_URLFORM = "application/x-www-form-urlencoded";

	private static final String EOL = "\r\n";
	private static final char DLM_QS = '?';
	private static final char DLM_QSPARAM = '&';
	private static final char DLM_QSVAL = '=';
	private static final char DLM_METHOD = ' ';
	private static final char DLM_HEADER = ':';

	private final com.grey.naf.BufferSpec bufspec;
	final long permcache;

	//pre-allocated purely for efficiency
	final StringBuilder sbtmp = new StringBuilder();
	private final com.grey.base.utils.ByteChars bctmp = new com.grey.base.utils.ByteChars();
	private final com.grey.base.utils.ByteChars tmplightbc = new com.grey.base.utils.ByteChars(-1); //lightweight object without own storage

	public HTTP(com.grey.naf.BufferSpec bufspec, long permcache)
		throws com.grey.base.ConfigException
	{
		this.bufspec = bufspec;
		this.permcache = permcache;
	}

	public String parseMethod(com.grey.base.utils.ArrayRef<byte[]> hdrline)
	{
		String method = null;
		if (isMatch(hdrline, METHOD_GET, DLM_METHOD)) {
			method = METHOD_GET;
		} else if (isMatch(hdrline, METHOD_POST, DLM_METHOD)) {
			method = METHOD_POST;
		} else if (isMatch(hdrline, METHOD_HEAD, DLM_METHOD)) {
			method = METHOD_HEAD;
		}
		return method;
	}

	public String parseURL(com.grey.base.utils.ArrayRef<byte[]> hdrline)
	{
		byte[] databuf = hdrline.ar_buf;
		int pos1 = ByteOps.indexOf(databuf, hdrline.ar_off, hdrline.ar_len, (byte)' '); //space between method and URL
		int pos2 = ByteOps.indexOf(databuf, hdrline.ar_off+pos1+1, hdrline.ar_len-pos1-1, (byte)' '); //space between URL and "HTTP/1.1"
		if (pos2 == -1) pos2 = hdrline.ar_off + hdrline.ar_len;
		return decodeURL(hdrline.ar_buf, pos1+1, pos2);
	}

	public String getURLPath(String url)
	{
		int pos_qry = url.indexOf(DLM_QS);
		String path = url.substring(1, pos_qry == -1 ? url.length() : pos_qry); //1st char is expected to be "/"
		return path;
	}

	public void parseQS(byte[] data, int off, int len, Command cmd)
	{
		String qs = decodeURL(data, off, off+len);
		parseQS(qs, cmd, false);
	}

	public void parseQS(String qs, Command cmd, boolean in_url)
	{
		if (in_url) {
			int pos = qs.indexOf(DLM_QS);
			if (pos == -1) return;
			qs = qs.substring(pos+1);
		}
		int lmt = qs.length();
		int nextpos = 0;
		while (nextpos < lmt) {
			int pos = nextpos;
			int dlm_param = qs.indexOf(DLM_QSPARAM, pos);
			if (dlm_param == -1) dlm_param = lmt;
			nextpos = dlm_param + 1;
			int dlm_val = qs.indexOf(DLM_QSVAL, pos);
			if (pos == dlm_val) continue; //no name
			if (dlm_val == -1 || dlm_val >= dlm_param-1) continue; //no value
			String pnam = qs.substring(pos, dlm_val);
			String pval = qs.substring(dlm_val+1, dlm_param);
			cmd.setArg(pnam, pval);
		}
	}

	public com.grey.base.utils.ByteChars parseHeaderValue(String hdrnam, com.grey.base.utils.ArrayRef<byte[]> hdrline)
	{
		if (!isMatch(hdrline, hdrnam, DLM_HEADER)) return null;
		int off = hdrline.ar_off + hdrnam.length() + 1; //+1 to step past DLM_HEADER
		while (hdrline.ar_buf[off] == ' ') off++;
		tmplightbc.pointAt(hdrline.ar_buf, off, hdrline.ar_len - (off - hdrline.ar_off));
		return tmplightbc;
	}

	public java.nio.ByteBuffer buildStaticResponse(byte[] body, String mimetype)
	{
		return buildResponse(null, body, mimetype, null, true);
	}

	public java.nio.ByteBuffer buildDynamicResponse(byte[] body, java.nio.ByteBuffer niobuf)
	{
		String mimetype = CTYPE_TEXT;
		if (body != null) {
			tmplightbc.pointAt(body, 0, body.length);
			if (isMatch(tmplightbc, "<!DOCTYPE HTML", (char)0) || isMatch(tmplightbc, "<HTML", (char)0)) {
				mimetype = CTYPE_HTML;
			} else if (body[0] == '<') {
				mimetype = CTYPE_XML;
			}
		}
		return buildResponse(null, body, mimetype, niobuf, false);
	}

	public java.nio.ByteBuffer buildErrorResponse(CharSequence status)
	{
		String body = "<html><head><title>Error</title></head><body>";
		body += "<h1>ERROR</h1><h2>"+status+"</h2></body></html>";
		return buildResponse(status, body.getBytes(), CTYPE_HTML, null, true);
	}

	private java.nio.ByteBuffer buildResponse(CharSequence status, byte[] body, String mimetype, java.nio.ByteBuffer niobuf, boolean perm)
	{
		com.grey.base.utils.ByteChars bc = bctmp;
		boolean cacheable = (perm && status == null);
		if (status == null) status = "200 OK";
		bc.set("HTTP/1.1 ").append(status).append(EOL);
		if (body != null) {
			bc.append(HDR_CTYPE).append(DLM_HEADER).append(" ").append(mimetype).append(EOL);
			bc.append(HDR_CLEN).append(DLM_HEADER).append(" ").append(body.length, sbtmp).append(EOL);
		}
		bc.append(HDR_CNX).append(DLM_HEADER).append(" close").append(EOL);
		bc.append(HDR_CACHECTL).append(DLM_HEADER);
		if (cacheable) {
			bc.append(" max-age=").append(permcache/1000, sbtmp);
		} else {
			bc.append(" no-cache");
		}
		bc.append(EOL).append(EOL);
		if (body != null) bc.append(body);
		if (niobuf != null && niobuf.capacity() < bc.length()) niobuf = null; //will have to recreate it
		niobuf = bufspec.encode(bc, niobuf);
		if (perm) niobuf = niobuf.asReadOnlyBuffer();
		return niobuf;
	}

	private String decodeURL(byte[] databuf, int off, int lmt)
	{
		StringBuilder sb = sbtmp;
		sb.setLength(0);
		for (int idx = off; idx != lmt; idx++) {
			int ch = databuf[idx] & 0xFF;
			if (ch == '%') {
				tmplightbc.pointAt(databuf, idx+1, 2);
				ch = (int)tmplightbc.parseHexadecimal();
				idx += 2;
			} else if (ch == '+') {
				//x-www-form-urlencoded does this, based on early percent-encoding rules (%20 is more canonical)
				ch = ' ';
			}
			sb.append((char)ch);
		}
		return sb.toString();
	}

	private static boolean isMatch(com.grey.base.utils.ArrayRef<byte[]> data, String token, char dlm)
	{
		int toklen = token.length();
		int minlen = (dlm == 0 ? toklen : toklen+1);
		int off = data.ar_off;
		if (data.ar_len < minlen || (dlm != 0 && data.ar_buf[off+toklen] != dlm)) return false;
		for (int idx = 0; idx != toklen; idx++) {
			if (Character.toUpperCase(data.ar_buf[off++]) != Character.toUpperCase(token.charAt(idx))) return false;
		}
		return true;
	}
}