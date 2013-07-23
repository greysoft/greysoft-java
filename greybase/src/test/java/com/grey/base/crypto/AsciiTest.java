/*
 * Copyright 2012 Yusef Badri - All rights reserved.
 * NAF is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.base.crypto;

import java.security.GeneralSecurityException;

import com.grey.base.config.SysProps;
import com.grey.base.utils.ByteOps;

public class AsciiTest
{
	private static final Ascii.ARMOURTYPE[] atypes = new Ascii.ARMOURTYPE[]{Ascii.ARMOURTYPE.BASE64, Ascii.ARMOURTYPE.HEX};

	// Cut'n'Paste block - this code fragment was generated by running LiceMan in GENKEYS mode
	// This defines the key's Modulus and it's public and private exponents.
	private static final java.math.BigInteger key_mod = new java.math.BigInteger("24405439838715524209389272163373516774078346423850627215961372656548987294640899606188186893338903013580458188474793647471084225518712500802217207191966982055696950818500335907052951011912739619014986121458305561043981161843234379446479227518432839865883282738562791898851415342023982796591431514384179786897782798826376494003371807989532272697412664948528622175526655846103333513929804083818231068471129728852009839813647525858290310324743306892717657936037862781176593575459245262430840812278313686490801013556908126895948244699521174768963230620290547540640572815717722211012613123275710837608053136577770348619877");
	private static final java.math.BigInteger key_pubexp = new java.math.BigInteger("65537");
	private static final java.math.BigInteger key_prvexp = new java.math.BigInteger("11885255930122597474203396712791692652417481795987253280202376820825144246696659167052232676012091316072354601879266888211042985514369412325916114328376614725874298218155495686551138814657475894235047332841956151277594378158729701609988724315704144485703203562628287322333060287410736453240339475610502190802442332163057676944358428556521558383473868139066917347513757655941418818532956266583420311422970879371850536063538370608587685585777786215556120869480866852951252298158090923992024201901583226356183280110822684417087680483217809663606962888998936018504787209824141920219946822149700552056039950587096755882641");

	@org.junit.Test
	public void testArmour()
	{
		byte[] data = new byte[64];
		for (byte idx = 0; idx != data.length; idx++) {
			data[idx] = idx;
		}
		for (int idx = 0; idx != atypes.length; idx++) {
			Ascii.ARMOURTYPE atype = atypes[idx];
			// vanilla encode/decode
			char[] wrapper = Ascii.armourWrap(data, 0, data.length, atype);
			byte[] data2 = Ascii.armourUnwrap(wrapper, 0, wrapper.length, atype);
			org.junit.Assert.assertArrayEquals(data, data2);
			// repeat with Unix-style end-of-lines
			String lfwrapper = new String(wrapper).replace("\r\n", "\n");
			data2 = Ascii.armourUnwrap(lfwrapper, atype);
			org.junit.Assert.assertArrayEquals(data, data2);
		}

		// repeat with offsets
		int boff = 3;
		int blen = data.length - boff - 2;
		for (int idx = 0; idx != atypes.length; idx++) {
			Ascii.ARMOURTYPE atype = atypes[idx];
			char[] wrapper = Ascii.armourWrap(data, boff, blen, atype);
			byte[] data2 = Ascii.armourUnwrap(wrapper, 0, wrapper.length, atype);
			org.junit.Assert.assertTrue(ByteOps.cmp(data, boff, data2, 0, blen));
		}

		// miscellaneous
		byte[] bdata = new byte[] {0, 1, 2, 127, (byte)128, (byte)255};
		char[] cdata = Ascii.hexEncode(bdata);
		byte[] bdata2 = Ascii.hexDecode(cdata);
		org.junit.Assert.assertArrayEquals(bdata, bdata2);

		cdata = Ascii.armourWrap(bdata, 0, bdata.length, Ascii.ARMOURTYPE.BASE64);
		String embedded = "leading noise"+new String(cdata)+"trailing noise";
		bdata2 = Ascii.armourUnwrap(embedded, Ascii.ARMOURTYPE.BASE64);
		org.junit.Assert.assertArrayEquals(bdata, bdata2);

		cdata = Ascii.armourWrap(bdata, 0, bdata.length, Ascii.ARMOURTYPE.HEX);
		embedded = "leading noise"+new String(cdata)+"trailing noise";
		bdata2 = Ascii.armourUnwrap(embedded, Ascii.ARMOURTYPE.HEX);
		org.junit.Assert.assertArrayEquals(bdata, bdata2);

		org.junit.Assert.assertNull(Ascii.armourUnwrap("missing armour", Ascii.ARMOURTYPE.BASE64));
		org.junit.Assert.assertNull(Ascii.armourUnwrap("missing armour", Ascii.ARMOURTYPE.HEX));
		org.junit.Assert.assertNull(Ascii.armourUnwrap(null, Ascii.ARMOURTYPE.BASE64));
		org.junit.Assert.assertNull(Ascii.armourUnwrap(null, Ascii.ARMOURTYPE.HEX));
	}

	@org.junit.Test
	public void testCrypto() throws GeneralSecurityException
	{
		// key generation might take a while, so use predefined keys by default
		java.math.BigInteger kmod = key_mod;
		java.math.BigInteger kpub = key_pubexp;
		java.math.BigInteger kprv = key_prvexp;
		if (SysProps.get("grey.test.genrsakeys", false)) {
			System.out.println("Generating RSA key pair ...");
			java.math.BigInteger[] keyparts = AsyKey.generateKeyPair();
			kmod = keyparts[0];
			kpub = keyparts[1];
			kprv = keyparts[2];
		}
		byte[] plaindata = new byte[10 * 1024];
		for (int idx = 0; idx != plaindata.length; idx++) plaindata[idx] = (byte)idx;
		// encrypt
		java.security.Key prvkey = AsyKey.buildPrivateKey(kmod, kprv);
		byte[] encdata = AsyKey.encryptData(prvkey, plaindata, 0, plaindata.length);
		char[] ascdata = Ascii.armourWrap(encdata, 0, encdata.length, Ascii.ARMOURTYPE.BASE64);
		// decrypt
		byte[] plaindata2 = Ascii.decrypt(new String(ascdata), kmod, kpub, Ascii.ARMOURTYPE.BASE64);
		org.junit.Assert.assertArrayEquals(plaindata, plaindata2);
	}
}
