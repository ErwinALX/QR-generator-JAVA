package MIQR;


import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


public final class QR {


       //version del qr
	public final int version;
	
        // dimension del qr
	public final int size;
	
        // tipo del nivel de correcion
	public final Ecc errorCorrectionLevel;
	
        //patron de mascara
	public final int mask;
	
	// variables para las grillas del qr
	private boolean[][] modules;     // modulos del qr  falso pinta blanco verdadero negro
	private boolean[][] isFunction;  // indica que modulos no deberian ser sugeridos para el masking
        
        
	 // Representa el nivel de corrección de errores utilizado en un símbolo de código QR.
	public enum Ecc {
		//Estas constantes de enumeración se deben declarar en orden ascendente de protección de error,
		
		LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2);
		
		final int formatBits;
		
		// Constructor.
		private Ecc(int fb) {
			formatBits = fb;
		}
	}
        // constructor que codifica segmentos del qr
	public static QR codificacionDeSegmentos(List<SementoQR> segs, Ecc ecl, int versionmin, int versionMAX, int mask, boolean boostEcl) {
		Objects.requireNonNull(segs);
		Objects.requireNonNull(ecl);
		if (!(1 <= versionmin && versionmin <= versionMAX && versionMAX <= 40) || mask < -1 || mask > 7)
			throw new IllegalArgumentException("Valor invalido");
		
		
		int version, bitsUsados;
		for (version = versionmin; ; version++) {
			int capacidadbits = getNumDataCodewords(version, ecl) * 8;  // numero de datos por bits disponibles
			bitsUsados = SementoQR.obtenerBitsTotales(segs, version);
			if (bitsUsados != -1 && bitsUsados <= capacidadbits)
				break;  
			if (version >= versionMAX)  
				throw new IllegalArgumentException("Datos muy largos");
		}
		if (bitsUsados == -1)
			throw new AssertionError();
				
		for (Ecc newEcl : Ecc.values()) {
			if (boostEcl && bitsUsados <= getNumDataCodewords(version, newEcl) * 8)
				ecl = newEcl;
		}
		
		// Concatena todos los segmentos de de bloques de bits del codigo
		int dataCapacityBits = getNumDataCodewords(version, ecl) * 8;
		BitBuffer bb = new BitBuffer();
		for (SementoQR seg : segs) {
			bb.apilarBits(seg.mode.modeBits, 4);
			bb.apilarBits(seg.numChars, seg.mode.numCharCountBits(version));
			bb.appendData(seg);
		}
		
		// Agrega un terminador de bits de acuerdo a la version  si es aplicable
		bb.apilarBits(0, Math.min(4, dataCapacityBits - bb.bitLength()));
		bb.apilarBits(0, (8 - bb.bitLength() % 8) % 8);
		
		for (int padByte = 0xEC; bb.bitLength() < dataCapacityBits; padByte ^= 0xEC ^ 0x11)
			bb.apilarBits(padByte, 8);
		if (bb.bitLength() % 8 != 0)
			throw new AssertionError();
		
		// crea simbolo qr
		return new QR(version, ecl, bb.obtenerBytes(), mask);
	}

	//constructor
        //Crea un nuevo símbolo de código QR con el número de versión especificado, el nivel de corrección de errores, la matriz de datos binarios y el número de máscara
	public QR(int ver, Ecc ecl, byte[] dataCodewords, int mask) {
		
		Objects.requireNonNull(ecl);
		if (ver < 1 || ver > 40 || mask < -1 || mask > 7)
			throw new IllegalArgumentException("valor fuera de rango");
		Objects.requireNonNull(dataCodewords);
		
		// Initialize fields
		version = ver;
		size = ver * 4 + 17;
		errorCorrectionLevel = ecl;
		modules = new boolean[size][size];  // Entirely white grid
		isFunction = new boolean[size][size];
		
		//  dibuja las funciones del patron con todo el el contenido del codigo
		dibujarFuncionPatrones();
		byte[] codigo = agregarNivelCorreccion(dataCodewords);
		drawCodewords(codigo);
		this.mask = manejarMascaraConstruccion(mask);
	}

        //regresa la matriz de modulo en binario simbolizando el color que va a tener
	public int obtenerModulo(int x, int y) {
		if (0 <= x && x < size && 0 <= y && y < size)
			return modules[y][x] ? 1 : 0;
		else
			return 0;  // borde blanco
	}

        //Devuelve la imagen del qr del buffer especificando la escala y el borde
	public BufferedImage toImage(int scale, int border) {
		if (scale <= 0 || border < 0)
			throw new IllegalArgumentException("valor fuera de rango");
		BufferedImage result = new BufferedImage((size + border * 2) * scale, (size + border * 2) * scale, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < result.getHeight(); y++) {
			for (int x = 0; x < result.getWidth(); x++) {
				int val = obtenerModulo(x / scale - border, y / scale - border);  // 0 o 1
				result.setRGB(x, y, val == 1 ? 0x000000 : 0xFFFFFF);
			}
		}
		return result;
	}
		
	// metodos para pintar el qr
	
	private void dibujarFuncionPatrones() {
		// Dibuja de horizontal a vertical patron de timing
		for (int i = 0; i < size; i++) {
			pintarModulos(6, i, i % 2 == 0);
			pintarModulos(i, 6, i % 2 == 0);
		}
		
		// Dibuja los 3 patrones de las esquinas()Draw 3 finder patterns (all corners except bottom right; overwrites some timing modules)
		dibujarPatronBuscado(3, 3);
		dibujarPatronBuscado(size - 4, 3);
		dibujarPatronBuscado(3, size - 4);
		
		// Dibuja los patrones de alineamiento
		int[] alignPatPos = obtenerAlineacionPosicionesPatrones(version);
		int numAlign = alignPatPos.length;
		for (int i = 0; i < numAlign; i++) {
			for (int j = 0; j < numAlign; j++) {
				if (i == 0 && j == 0 || i == 0 && j == numAlign - 1 || i == numAlign - 1 && j == 0)
					continue;  
				else
					dibujarPatronAlineamiento(alignPatPos[i], alignPatPos[j]);
			}
		}
		
		// dibuja la configuracion de los datos
		dibujarFormatoBits(0);  
		dibujarVersion();
	}
	
	// crea un dibujo copia en formato de bits con los errores de correcion basado en la mascara dada
	private void dibujarFormatoBits(int mask) {
		// calcula el error de correcion del codigo y los bits
		int data = errorCorrectionLevel.formatBits << 3 | mask;  // errCorrLvl is uint2, mask is uint3
		int rem = data;
		for (int i = 0; i < 10; i++)
			rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
		data = data << 10 | rem;
		data ^= 0x5412;  // uint15
		if (data >>> 15 != 0)
			throw new AssertionError();
		
		// dibuja la primera copia
		for (int i = 0; i <= 5; i++)
			pintarModulos(8, i, ((data >>> i) & 1) != 0);
		pintarModulos(8, 7, ((data >>> 6) & 1) != 0);
		pintarModulos(8, 8, ((data >>> 7) & 1) != 0);
		pintarModulos(7, 8, ((data >>> 8) & 1) != 0);
		for (int i = 9; i < 15; i++)
			pintarModulos(14 - i, 8, ((data >>> i) & 1) != 0);
		
		// dibuja la segunda copia
		for (int i = 0; i <= 7; i++)
			pintarModulos(size - 1 - i, 8, ((data >>> i) & 1) != 0);
		for (int i = 8; i < 15; i++)
			pintarModulos(8, size - 15 + i, ((data >>> i) & 1) != 0);
		pintarModulos(8, size - 8, true);
	}
	
	
	// Dibuja 2 copias de la version de los bits Draws two copies of the version bits (with its own error correction code),
	// based on this object's version field (which only has an effect for 7 <= version <= 40).
	private void dibujarVersion() {
		if (version < 7)
			return;
		
		
		int rem = version;  
		for (int i = 0; i < 12; i++)
			rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
		int data = version << 12 | rem;  
		if (data >>> 18 != 0)
			throw new AssertionError();
		
		
		for (int i = 0; i < 18; i++) {
			boolean bit = ((data >>> i) & 1) != 0;
			int a = size - 11 + i % 3, b = i / 3;
			pintarModulos(a, b, bit);
			pintarModulos(b, a, bit);
		}
	}
	
	
	// dibuja bloques 9*9 del patron incluyendo el borde separador en un modulo cnetrado
	private void dibujarPatronBuscado(int x, int y) {
		for (int i = -4; i <= 4; i++) {
			for (int j = -4; j <= 4; j++) {
				int dist = Math.max(Math.abs(i), Math.abs(j));  
				int xx = x + j, yy = y + i;
				if (0 <= xx && xx < size && 0 <= yy && yy < size)
					pintarModulos(xx, yy, dist != 2 && dist != 4);
			}
		}
	}

	// dibuja bloques de 5*5 de patron de alineamiento con modulos centrados
	private void dibujarPatronAlineamiento(int x, int y) {
		for (int i = -2; i <= 2; i++) {
			for (int j = -2; j <= 2; j++)
				pintarModulos(x + j, y + i, Math.max(Math.abs(i), Math.abs(j)) != 1);
		}
	}

	// pone los colores blanco y negro a los modulos
	private void pintarModulos(int x, int y, boolean isBlack) {
		modules[y][x] = isBlack;
		isFunction[y][x] = true;
	}
	
        //agrega el nivel de correccion al los datos del qr
	private byte[] agregarNivelCorreccion(byte[] data) {
		if (data.length != getNumDataCodewords(version, errorCorrectionLevel))
			throw new IllegalArgumentException();
		
		// calcula los parametros
		int numeroBloques = NUM_ERROR_CORRECTION_BLOCKS[errorCorrectionLevel.ordinal()][version];
		int blockEccLen = ECC_CODEWORDS_PER_BLOCK[errorCorrectionLevel.ordinal()][version];
		int filaCodigos = obtenerNumeroFilasModuloDatos(version) / 8;
		int numBloquesCorto = numeroBloques - filaCodigos % numeroBloques;
		int shortBlockLen = filaCodigos / numeroBloques;
		
		// partir los datos en bloques y agregar el error de nivel de correcion para cada bloque
		byte[][] bloquesdeBites = new byte[numeroBloques][];
		ReedSolomonGenerator rs = new ReedSolomonGenerator(blockEccLen);
		for (int i = 0, k = 0; i < numeroBloques; i++) {
			byte[] dat = Arrays.copyOfRange(data, k, k + shortBlockLen - blockEccLen + (i < numBloquesCorto ? 0 : 1));
			byte[] block = Arrays.copyOf(dat, shortBlockLen + 1);
			k += dat.length;
			byte[] ecc = rs.obtenerBitsRecordatorio(dat);
			System.arraycopy(ecc, 0, block, block.length - blockEccLen, ecc.length);
			bloquesdeBites[i] = block;
		}
		
		// Interleave (not concatenate) the bytes from every block into a single sequence
		byte[] result = new byte[filaCodigos];
		for (int i = 0, k = 0; i < bloquesdeBites[0].length; i++) {
			for (int j = 0; j < bloquesdeBites.length; j++) {
				// salta padding byte en pequenios bloques de bits
				if (i != shortBlockLen - blockEccLen || j >= numBloquesCorto) {
					result[k] = bloquesdeBites[j][i];
					k++;
				}
			}
		}
		return result;
	}
	
	
	// Dibuja la secuencia de 8 bits por palabra	
	private void drawCodewords(byte[] data) {
		Objects.requireNonNull(data);
		if (data.length != obtenerNumeroFilasModuloDatos(version) / 8)
			throw new IllegalArgumentException();
		
		int i = 0;  // indice de bits dentro de los datos
		// Do the funny zigzag scan
		for (int right = size - 1; right >= 1; right -= 2) {  // Index of right column in each column pair
			if (right == 6)
				right = 5;
			for (int vert = 0; vert < size; vert++) {  // contador verticalVertical counter
				for (int j = 0; j < 2; j++) {
					int x = right - j;  //coordenada x actual Actual x coordinate
					boolean upward = ((right + 1) & 2) == 0;
					int y = upward ? size - 1 - vert : vert;  // coordenada de y actual
					if (!isFunction[y][x] && i < data.length * 8) {
						modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
						i++;
					}
					
				}
			}
		}
		if (i != data.length * 8)
			throw new AssertionError();
	}
	
	// aplicamos operacion XOR al patron de la mascara 
	
	private void aplicarXORmascara(int mask) {
		if (mask < 0 || mask > 7)
			throw new IllegalArgumentException("Mascara fuera del rango");
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				boolean invert;
				switch (mask) {
					case 0:  invert = (x + y) % 2 == 0;                    break;
					case 1:  invert = y % 2 == 0;                          break;
					case 2:  invert = x % 3 == 0;                          break;
					case 3:  invert = (x + y) % 3 == 0;                    break;
					case 4:  invert = (x / 3 + y / 2) % 2 == 0;            break;
					case 5:  invert = x * y % 2 + x * y % 3 == 0;          break;
					case 6:  invert = (x * y % 2 + x * y % 3) % 2 == 0;    break;
					case 7:  invert = ((x + y) % 2 + x * y % 3) % 2 == 0;  break;
					default:  throw new AssertionError();
				}
				modules[y][x] ^= invert & !isFunction[y][x];
			}
		}
	}
	
	
	// Una función auxiliar desordenada para los constructores. Este código QR debe estar en un estado no enmascarado cuando
	// Método. El argumento dado es la máscara solicitada, que es -1 para auto o 0 a 7 para fixed.
	private int manejarMascaraConstruccion(int mask) {
		if (mask == -1) {  // Automatically choose best mask
			int minPenalty = Integer.MAX_VALUE;
			for (int i = 0; i < 8; i++) {
				dibujarFormatoBits(i);
				aplicarXORmascara(i);
				int penalty = obtenerCostoPenalidad();
				if (penalty < minPenalty) {
					mask = i;
					minPenalty = penalty;
				}
				aplicarXORmascara(i);  // Undoes the mask due to XOR
			}
		}
		if (mask < 0 || mask > 7)
			throw new AssertionError();
		dibujarFormatoBits(mask);  // sobreescribe el formato viejo de los bits
		aplicarXORmascara(mask);  // aplica la seleccion final de mascara
		return mask;  
	}
	
	
	// Calcula y devuelve el puntaje de penalización basado en el estado de los módulos actuales de este codigo QR.
	// Esto es utilizado por el algoritmo de elección de máscara automática para encontrar el patrón de máscara que produce la puntuación más baja.
	private int obtenerCostoPenalidad() {
		int result = 0;
		
		// Modulos adyacentes en fila teniendo el mismo color
		for (int y = 0; y < size; y++) {
			boolean colorX = false;
			for (int x = 0, runX = 0; x < size; x++) {
				if (x == 0 || modules[y][x] != colorX) {
					colorX = modules[y][x];
					runX = 1;
				} else {
					runX++;
					if (runX == 5)
						result += PENALTY_N1;
					else if (runX > 5)
						result++;
				}
			}
		}
		// Modulos adyacentes en columnas teniendo el mismo color
		for (int x = 0; x < size; x++) {
			boolean colorY = false;
			for (int y = 0, runY = 0; y < size; y++) {
				if (y == 0 || modules[y][x] != colorY) {
					colorY = modules[y][x];
					runY = 1;
				} else {
					runY++;
					if (runY == 5)
						result += PENALTY_N1;
					else if (runY > 5)
						result++;
				}
			}
		}
		
		// bloques de modulos de 2*2 teniendo el mismo color
		for (int y = 0; y < size - 1; y++) {
			for (int x = 0; x < size - 1; x++) {
				boolean color = modules[y][x];
				if (  color == modules[y][x + 1] &&
				      color == modules[y + 1][x] &&
				      color == modules[y + 1][x + 1])
					result += PENALTY_N2;
			}
		}
		
		// Patrón de búsqueda en filas
		for (int y = 0; y < size; y++) {
			for (int x = 0, bits = 0; x < size; x++) {
				bits = ((bits << 1) & 0x7FF) | (modules[y][x] ? 1 : 0);
				if (x >= 10 && (bits == 0x05D || bits == 0x5D0))  // Needs 11 bits accumulated
					result += PENALTY_N3;
			}
		}
		// Patrón de búsqueda en columnas
		for (int x = 0; x < size; x++) {
			for (int y = 0, bits = 0; y < size; y++) {
				bits = ((bits << 1) & 0x7FF) | (modules[y][x] ? 1 : 0);
				if (y >= 10 && (bits == 0x05D || bits == 0x5D0))  // Needs 11 bits accumulated
					result += PENALTY_N3;
			}
		}
		
		// Balance de módulos en blanco y negro
		int black = 0;
		for (boolean[] row : modules) {
			for (boolean color : row) {
				if (color)
					black++;
			}
		}
		int total = size * size;
		// Hallar el k más pequeño tal que (45-5k)% <= oscuro / total <= (55 + 5k)%
		for (int k = 0; black*20 < (9-k)*total || black*20 > (11+k)*total; k++)
			result += PENALTY_N4;
		return result;
	}

	
	
	//Devuelve un conjunto de posiciones de los patrones de alineación en orden ascendente. 
        //Estas posiciones se usan tanto en los ejes xey. Cada valor en la matriz resultante está en el rango [0, 177].
	//Esta función pura sin estado podría implementarse como tabla de 40 listas de longitud variable de bytes sin signo.
	private static int[] obtenerAlineacionPosicionesPatrones(int ver) {
		if (ver < 1 || ver > 40)
			throw new IllegalArgumentException("Version fuera del rango");
		else if (ver == 1)
			return new int[]{};
		else {
			int numAlign = ver / 7 + 2;
			int step;
			if (ver != 32)
				step = (ver * 4 + numAlign * 2 + 1) / (2 * numAlign - 2) * 2;
			else  
				step = 26;
			
			int[] result = new int[numAlign];
			int size = ver * 4 + 17;
			result[0] = 6;
			for (int i = result.length - 1, pos = size - 7; i >= 1; i--, pos -= step)
				result[i] = pos;
			return result;
		}
	}
	
	
	//Devuelve el número de bits de datos que se pueden almacenar en un código QR del número de versión dado, después de excluir todos los módulos de función. Esto incluye bits de resto, por lo que puede no ser un múltiplo de 8.
	
	private static int obtenerNumeroFilasModuloDatos(int ver) {
		if (ver < 1 || ver > 40)
			throw new IllegalArgumentException("Version fuera del rango");
		
		int size = ver * 4 + 17;
		int result = size * size;   // numero de modulos en el cuadro entero de QR
		result -= 64 * 3;           // substrae los 3 separadores finders con separadores
		result -= 15 * 2 + 1;       // substrae el formato de la informacion y el modulo negro
		result -= (size - 16) * 2;  // substrae los patrones timing
		
		if (ver >= 2) {
			int numAlign = ver / 7 + 2;
			result -= (numAlign - 1) * (numAlign - 1) * 25;  // substrae los patrones de alineamiento junto con los patrones timing
			result -= (numAlign - 2) * 2 * 20;  
			
			if (ver >= 7)
				result -= 18 * 2;  // substrae la informacion de la version
		}
		return result;
	}

	// Devuelve el número de palabras de código de 8 bits (es decir, no de corrección de errores) contenidas en cualquier código QR del número de versión y nivel de corrección de errores dados, con los bits de resto eliminados.
	
	static int getNumDataCodewords(int ver, Ecc ecl) {
		if (ver < 1 || ver > 40)
			throw new IllegalArgumentException("Version fuera del rango");
		return obtenerNumeroFilasModuloDatos(ver) / 8 - ECC_CODEWORDS_PER_BLOCK[ecl.ordinal()][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal()][ver];
	}

	// se usan para  obtenerCostoPenalidad(), y para obtener la mejor mascara para el algoritmo del masqueo.
	private static final int PENALTY_N1 = 3;
	private static final int PENALTY_N2 = 3;
	private static final int PENALTY_N3 = 40;
	private static final int PENALTY_N4 = 10;
	
	
	private static final byte[][] ECC_CODEWORDS_PER_BLOCK = {
		
		
		{-1,  7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},  // Low
		{-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28},  // Medium
		{-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},  // Quartile
		{-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30},  // High
	};
	
	private static final byte[][] NUM_ERROR_CORRECTION_BLOCKS = {
		

		{-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4,  4,  4,  4,  4,  6,  6,  6,  6,  7,  8,  8,  9,  9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25},  // Low
		{-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5,  5,  8,  9,  9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49},  // Medium
		{-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8,  8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68},  // Quartile
		{-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81},  // High
	};

	
        
	//Calcula las palabras de código de corrección de errores de Reed-Solomon para una secuencia de palabras de código de datos en un grado dado.
        //Los objetos son inmutables y el estado sólo depende del grado. Esta clase existe porque el polinomio del divisor no necesita ser recalculado para cada entrada.
	
       
	private static final class ReedSolomonGenerator {
	
		// Coeficientes del polinomio del divisor, almacenados de mayor a menor potencia, excluyendo el término principal que es siempre 1.
		private final byte[] coeficientes;
	
		/*-- Constructor --*/
		//Crea un generador ECC Reed-Solomon para el grado especificado. Esto podría ser implementado como una tabla de consulta sobre todos los posibles valores de parámetros, en lugar de como un algoritmo.
		
		public ReedSolomonGenerator(int degree) {// el parametro degree es el polinomio divisor
			if (degree < 1 || degree > 255)
				throw new IllegalArgumentException("Grado fuera de rango");
			
			// empieza con el monomial x a la 0
			coeficientes = new byte[degree];
			coeficientes[degree - 1] = 1;
			
			// calcula el producto polinomial (x - r^0) * (x - r^1) * (x - r^2) * ... * (x - r^{degree-1}),
			// arroja el termino mas alto y lo almacena en el resto de los coeficientes en orden descendente(potencias).
			
			int root = 1;
			for (int i = 0; i < degree; i++) {
				// multiplica el producto actual por (x - r^i)
				for (int j = 0; j < coeficientes.length; j++) {
					coeficientes[j] = (byte)multiplicar(coeficientes[j] & 0xFF, root);
					if (j + 1 < coeficientes.length)
						coeficientes[j] ^= coeficientes[j + 1];
				}
				root = multiplicar(root, 0x02);
			}
		}
		// Calcula y devuelve las palabras clave de corrección de errores de Reed-Solomon para la secuencia especificada de palabras de código de datos.
		public byte[] obtenerBitsRecordatorio(byte[] data) {
			Objects.requireNonNull(data);
			
			// Calcula el reemisor para optimizar la division polinomial
			byte[] result = new byte[coeficientes.length];
			for (byte b : data) {
				int factor = (b ^ result[0]) & 0xFF;
				System.arraycopy(result, 1, result, 0, result.length - 1);
				result[result.length - 1] = 0;
				for (int i = 0; i < result.length; i++)
					result[i] ^= multiplicar(coeficientes[i] & 0xFF, factor);
			}
			return result;
		}
		
		
		// Devuelve el producto de los dos elementos de campo modulo GF (2 ^ 8 / 0x11D)
		// Los argumentos y el resultado son enteros sin signo de 8 bits. Esto podría ser implementado como una tabla de búsqueda de 256 * 256 entradas de uint8.
		private static int multiplicar(int x, int y) {
			if (x >>> 8 != 0 || y >>> 8 != 0)
				throw new IllegalArgumentException("Byte fuera de rango");
			
			int z = 0;
			for (int i = 7; i >= 0; i--) {
				z = (z << 1) ^ ((z >>> 7) * 0x11D);
				z ^= ((y >>> i) & 1) * x;
			}
			if (z >>> 8 != 0)
				throw new AssertionError();
			return z;
		}
		
	}
	
}
