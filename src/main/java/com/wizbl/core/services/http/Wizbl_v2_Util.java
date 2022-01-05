package com.wizbl.core.services.http;

import org.apache.commons.lang3.StringUtils;

public class Wizbl_v2_Util {
	/**
	 * Hex -> 10진수로 변경
	 * 
	 * @param hex
	 * @return
	 */
	public static String getHexToDec(String hex) {
		long v = Long.parseLong(hex, 16);
		return String.valueOf(v);
	}

	/**
	 * 10진수 -> Hex 값으로 변경
	 * 
	 * @param dec
	 * @return
	 */
	public static String getDecToHex(String dec) {

		Long intDec = Long.parseLong(dec);
		return Long.toHexString(intDec).toUpperCase();
	}

	// string 값을 UTF-8로 인코딩 하여 Hex값으로 저장.
	public static String stringToHex(String input) {
		String result = "";
		try {
			byte[] str = input.getBytes("UTF-8");

			for (int i = 0; i < str.length; i++) {
				result += String.format("%02x", str[i]);
			}

		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}
	
	public static String concatWord(String input) {
		// 입력 String을 압축한다.
		String coreWord = CompressStringUtil.compressString(input);
		int coreWordlen = coreWord.length();
		
		//String 타입은 Wizbl_v2 Solidity에서  앞에 firstString과 같이 prefix가 붙는다.
		String firstString ="0000000000000000000000000000000000000000000000000000000000000020";
		String secoundString = String.format("%064x", coreWordlen/2);
		String finalString ="00000000000000000000000000000000000000000000000000000000000000";
		String coreWord2 = StringUtils.rightPad(coreWord, 64, '0');
		
		String resultA = "";
	    if(coreWordlen % 2 != 0 && coreWordlen>64) {
	    	resultA =firstString.concat(secoundString).concat(coreWord.concat(finalString));
	    	System.out.println("result1 :" + resultA);
	    	System.out.println("result1 길이  :" + resultA.length());
	    } else if(coreWordlen<=64) {
	    	resultA=firstString.concat(secoundString).concat(coreWord2);
	    	System.out.println("result2 : " + resultA);
	    	System.out.println("result2 길이  : " + resultA.length());
	    } else {
	    	resultA =firstString.concat(secoundString).concat(coreWord);
	    	System.out.println("result3 :" + resultA);
	    	System.out.println("result3 길이 :" + resultA.length());
	    }
	    
		return resultA;
	}
	
	public static String wizblHexToString(String input){
		String minusInput = input.substring(64,128);
		int minusInputlen = Integer.parseInt(getHexToDec(minusInput));
		String coreWord = input.substring(128,(128+(minusInputlen*2)));
		
		//String 압축을 해제한다.
		String result = CompressStringUtil.decompressString(coreWord);
		
		return result;
	}

}
