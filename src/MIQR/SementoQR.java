package MIQR;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

//Representa una cadena de caracteres a codificar en un símbolo de código QR
public final class SementoQR {
	

    // codifica a alfanumerico con la clase bitbuffer
	public static SementoQR generarAlfanumerico(String text) {
		Objects.requireNonNull(text);
		if (!alfanumerico_regex.matcher(text).matches())
			throw new IllegalArgumentException("String contains unencodable characters in alphanumeric mode");
		
		BitBuffer bb = new BitBuffer();
		int i;
		for (i = 0; i + 2 <= text.length(); i += 2) {  // Process groups of 2
			int temp = alfanumerico_charset.indexOf(text.charAt(i)) * 45;
			temp += alfanumerico_charset.indexOf(text.charAt(i + 1));
			bb.apilarBits(temp, 11);
		}
		if (i < text.length())  // 1 character remaining
			bb.apilarBits(alfanumerico_charset.indexOf(text.charAt(i)), 6);
		return new SementoQR(Mode.ALPHANUMERIC, text.length(), bb.obtenerBytes(), bb.bitLength());
	}

        // genera los segmentos para la cadena recibida
        //Devuelve una nueva lista mutable de cero o más segmentos para representar la cadena de texto Unicode especificada. El resultado puede utilizar varios modos de segmento y modos de conmutación para optimizar la longitud de la secuencia de bits.
	public static List<SementoQR> generarSegmentos(String text) {
		Objects.requireNonNull(text);
		
		// Select the most efficient segment encoding automatically
		List<SementoQR> result = new ArrayList<>();
		if (text.equals(""))
			return result;
		
		else if (alfanumerico_regex.matcher(text).matches())
			result.add(generarAlfanumerico(text));
	
		return result;
	}

        // modo de codificacion
	public final Mode mode;
	
	// La longitud de los datos no codificados de este segmento, medidos en caracteres. Siempre cero o positivo.
	public final int numChars;
	
	// arrays de datos.
	private final byte[] data;
	
	//longitud de los datos codificados en segmentos
	public final int bitLength;

        // constructor que genera los segmentos
	public SementoQR(Mode md, int numCh, byte[] b, int bitLen) {
		Objects.requireNonNull(md);
		Objects.requireNonNull(b);
		if (numCh < 0 || bitLen < 0 || bitLen > b.length * 8L)
			throw new IllegalArgumentException("Invalid value");
		mode = md;
		numChars = numCh;
		data = Arrays.copyOf(b, (bitLen + 7) / 8);  // Trim to precise length and also make defensive copy
		bitLength = bitLen;
	}

        //Devuelve el byte de datos en el índice especificado
	public byte obtenerBytes(int index) {
		if (index < 0 || index > data.length)
			throw new IndexOutOfBoundsException();
		return data[index];
	}
	
	

	static int obtenerBitsTotales(List<SementoQR> segs, int version) {
		Objects.requireNonNull(segs);
		if (version < 1 || version > 40)
			throw new IllegalArgumentException("Version number out of range");
		
		long result = 0;
		for (SementoQR seg : segs) {
			Objects.requireNonNull(seg);
			int ccbits = seg.mode.numCharCountBits(version);
			
			if (seg.numChars >= (1 << ccbits))
				return -1;
			result += 4L + ccbits + seg.bitLength;
			if (result > Integer.MAX_VALUE)
				return -1;
		}
		return (int)result;
	}

	//constantes de expresiones regulares para codificacion alfanumerica
	
	public static final Pattern alfanumerico_regex = Pattern.compile("[A-Z0-9 $%*+./:-]*");
	
	private static final String alfanumerico_charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
	
        //tipo de codificacion
	public enum Mode {
		
		
		
		ALPHANUMERIC(0x2,  9, 11, 13);
	
		final int modeBits;
		
		private final int[] numBitsCharCount;
	
		//constructor
		private Mode(int mode, int... ccbits) {
			this.modeBits = mode;
			numBitsCharCount = ccbits;
		}
	
                //Devuelve el ancho de bit del campo de recuento de caracteres de segmento para este objeto de modo en el número de versión especificado.
		int numCharCountBits(int ver) {
			if      ( 1 <= ver && ver <=  9)  return numBitsCharCount[0];			
			else  throw new IllegalArgumentException("numero de version fuera de rango");
		}
		
	}
	
}
