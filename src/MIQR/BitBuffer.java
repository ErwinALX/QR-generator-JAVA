package MIQR;

import java.util.Arrays;
import java.util.Objects;



// esta clase se encarga de codificar en binario de lenguaje maquina a bajo nivel la cadena de datos que se reciba
final class BitBuffer {

	private byte[] datos;
	private int longitudBit;

	
        // contructor 
        // crea un buffer vacio
	public BitBuffer() {
		datos = new byte[16];
		longitudBit = 0;
	}

        //retorna el numero de bits en el buffer
	public int bitLength() {
		return longitudBit;
	}

        //retorna una copia de todos los bytes 
	public byte[] obtenerBytes() {
		return Arrays.copyOf(datos, (longitudBit + 7) / 8);
	}

        //apila dado un numero de bits de los valores dados en la secuencia
	public void apilarBits(int val, int len) {
		if (len < 0 || len > 32 || len < 32 && (val >>> len) != 0)
			throw new IllegalArgumentException("valor fuera de rango");
		asegurarCapacidad(longitudBit + len);
		for (int i = len - 1; i >= 0; i--, longitudBit++)  // apila de bit a bit
			datos[longitudBit >>> 3] |= ((val >>> i) & 1) << (7 - (longitudBit & 7));
	}
	
	
	
        //agrega los datos de un segmento para este buffer
	public void appendData(SementoQR seg) {
		Objects.requireNonNull(seg);
		asegurarCapacidad(longitudBit + seg.bitLength);
		for (int i = 0; i < seg.bitLength; i++, longitudBit++) {  // apila de bit a bit
			int bit = (seg.obtenerBytes(i >>> 3) >>> (7 - (i & 7))) & 1;
			datos[longitudBit >>> 3] |= bit << (7 - (longitudBit & 7));
		}
	}

        //expande la capacidad del buffer para mantener el tamano de los bits de datos
	private void asegurarCapacidad(int newBitLen) {
		while (datos.length * 8 < newBitLen)
			datos = Arrays.copyOf(datos, datos.length * 2);
	}
	
}
