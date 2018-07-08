//Emisor
import javax.swing.*;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;

import static java.util.Arrays.copyOfRange;

public class EmisorUDP {
    static int tamDatos = 116; //Tamaño de los datos + mas checksum 8 + numero de secuencia 4 = 128.
    static int tamVentana = 10; //Tamaño inicial de la ventana.
    static int valorTimeout = 5000; //5000 milisegundos.

    int base; //Numero de secuencia base de la ventana.
    int sigNumSecuencia; //Siguiente número de secuencia en la ventana.
    Vector<byte[]> listaPaquetes; //Lista de paquetes generados.
    Timer timer; //Para el temporizador de retransmisión.
    Semaphore sem;
    boolean transferenciaCompleta; //Si el receptor ha recibido completamente el string.
    boolean hayMasDatos = true;


    /**
     * Metodo  para iniciar o finalizar el temporizador.
     * @param nuevoTimer Boolean para decidir si se inicia o finaliza el timer.
     */
    public void setTimer(boolean nuevoTimer) {
        if(timer != null) {
            timer.cancel();
        }
        if(nuevoTimer == true) {
            timer = new Timer();
            timer.schedule(new Timeout(), valorTimeout);
        }
    }

    //Clase que crea el hilo de salida.
    public class HiloSalida extends Thread {
        private DatagramSocket socketSalida;
        private int puertoDest1; //Puerto destino.
        private int puertoDest2;
        private InetAddress direccionDest; //Direccion de destino.
        static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"; //Caracteres para el mensaje.
        private SecureRandom rnd = new SecureRandom(); //Generador random.
        int indice = 0;

        /**
         * Constructor de la clase HiloSalida.
         * @param sk Socket.
         * @param puerto1 Puerto destino.
         */
        public HiloSalida(DatagramSocket sk, int puerto1, int puerto2) {
            this.socketSalida = sk;
            this.puertoDest1 = puerto1;
            this.puertoDest2 = puerto2;
        }

        /**
         * Metodo que construye el paquete antepuesto con información de encabezado.
         * @param numSecuencia Numero de secuencia del paquete.
         * @param bytesDatos Datos en bytes.
         * @return Devuelve el paquete.
         */
        public byte[] generarPaquete(int numSecuencia, byte[] bytesDatos) {
            byte[] numSecuenciaBytes = ByteBuffer.allocate(4).putInt(numSecuencia).array(); //Numero de secuencia, 4 bytes.

            CRC32 checksum = new CRC32(); //Para generar el checksum.
            checksum.update(numSecuenciaBytes);
            checksum.update(bytesDatos);
            byte[] checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array(); //Checksum, 8 bytes.

            ByteBuffer paqueteBuffer = ByteBuffer.allocate(8 + 4 + bytesDatos.length); //Generar paquete.
            paqueteBuffer.put(checksumBytes);
            paqueteBuffer.put(numSecuenciaBytes);
            paqueteBuffer.put(bytesDatos);

            return paqueteBuffer.array();
        }

        /**
         * Método para generar una hilera al azar. Se utilizará para el mensaje.
         * @param len Tamaño de la hilera que se genera.
         * @return Devuelve la hilera generada al azar.
         */
        String randomString(int len){
            StringBuilder sb = new StringBuilder( len );
            for( int i = 0; i < len; i++ )
                sb.append( AB.charAt( rnd.nextInt(AB.length()) ) );
            return sb.toString();
        }

        /**
         * Método que lee un numero de bytes (116) de la hilera de mensaje.
         * @param dataBuffer El buffer donde se va a escribir.
         * @param bytesMensaje El arreglo desde donde se leen los bytes del mensaje.
         */
        public int leerDatos(byte[] dataBuffer, byte[] bytesMensaje) {
            int r = 0;
            boolean finString = false;
            int indInicial = indice;
            for(int i = indice; i < (indInicial+tamDatos) && finString == false && indice < bytesMensaje.length; i++) {
                dataBuffer[r] = bytesMensaje[i];
                indice++;
                r++;
                if(indice >= bytesMensaje.length){
                    r = -1;
                    finString = true;
                }
            }
            return r;
        }

        public void run() {
            try {
                direccionDest = InetAddress.getByName("127.0.0.1");
                String mensaje = randomString(1000);
                try {
                    while(!transferenciaCompleta) { //Mientras todavía hay paquetes por recibir por el receptor.
                        if((sigNumSecuencia < base + tamVentana) && hayMasDatos == true) { //Si la ventana aun no está llena y quedan datos, enviar paquetes.
                            sem.acquire();

                            if(base == sigNumSecuencia) {
                                setTimer(true); //Si es el primer paquete de la ventana, inicie el temporizador.
                            }
                            byte[] datosSalida = new byte[10];
                            boolean esFinal = false;

                            if(sigNumSecuencia < listaPaquetes.size()) { //Si el paquete está en la lista de paquetes recuperelo de ahí.
                                datosSalida = listaPaquetes.get(sigNumSecuencia);
                            }
                            else { //Si no, crea el paquete y lo agrega a la lista.
                                if(sigNumSecuencia == 0) { //Si es el primer paquete crea el string
                                    byte[] datosBuffer = new byte[tamDatos];  //arreglo de 116 bytes para ir leyendo del string.
                                    byte[] bytesMensaje = mensaje.getBytes(); //Arreglo que contiene los bytes del mensaje.
                                    int tam = leerDatos(datosBuffer, bytesMensaje); //Variable que contiene la cantidad de bytes que se pudieron leer del mensaje.
                                    byte[] datosBytes = copyOfRange(datosBuffer, 0, tam); //Arreglo de tamaño variable de los bytes que se leyeron.
                                    datosSalida = generarPaquete(sigNumSecuencia, datosBytes); //Genera el paquete.
                                }
                                else { //Si no, concatena a la string.
                                    byte[] datosBuffer = new byte[tamDatos];  //arreglo de 116 bytes para ir leyendo del string.
                                    byte[] bytesMensaje = mensaje.getBytes(); //Arreglo que contiene los bytes del mensaje.
                                    int tam = leerDatos(datosBuffer, bytesMensaje); //Variable que contiene la cantidad de bytes que se pudieron leer del mensaje.
                                    if(tam == -1) { // Si no hay mas datos por leer, envíe datos vacíos. Indique que es el último num de secuencia.
                                        esFinal = true;
                                        datosSalida = generarPaquete(sigNumSecuencia, new byte[0]);
                                    }
                                    else { //Si hay mas datos por leer.
                                        byte[] datosBytes = copyOfRange(datosBuffer, 0, tam); //Arreglo de tamaño variable de los bytes que se leyeron.
                                        datosSalida = generarPaquete(sigNumSecuencia, datosBytes); //Genera el paquete.
                                    }
                                }
                                listaPaquetes.add(datosSalida);  //Se agrega el paquete a la lista.
                            }
                            SecureRandom r = new SecureRandom(); //Random para simular el 20% de pérdida de los paquetes.
                            int porcentajePerdida = r.nextInt(99);  //Genera un número al azar entre 0 y 99.
                            DatagramPacket ps = new DatagramPacket(datosSalida, datosSalida.length, direccionDest, puertoDest1);
                            if(ps.getLength() == 12) {
                                hayMasDatos = false;
                            }
                            if(porcentajePerdida < 80) { //Si el número es menor a 80 se envía el paquete, de lo contrario se desecha el paquete y no se envía por la red.
                                socketSalida.send(ps); //Se envía el paquete a traves del socket.
                                System.out.println("Emisor: Número de secuencia enviado: " + sigNumSecuencia);
                            } else {
                                System.out.println("Emisor: Paquete: " + sigNumSecuencia + " descartado. (Simulando 20% de pérdida). ");
                            }

                            //Si actualmente no se está en el número de secuencia final, se actualiza el siguiente numero de secuencia.
                            if(!esFinal) {
                                sigNumSecuencia++;
                            }
                            sem.release();
                        }
                        sleep(500); //Para generar paquetes cada medio segundo.
                    }
                }
                catch(Exception e) {
                    e.printStackTrace();
                }
                finally {
                    setTimer(false); //Finaliza el temporizador.
                    socketSalida.close(); //Se cierra el socket.
                    System.out.println("Emisor: Socket de salida cerrado.");
                }
            }
            catch(Exception e) {
                e.printStackTrace();
                System.exit(-1);
            }
        }
    }

    //ClASE HiloEntrada.
    public class HiloEntrada extends Thread {
        private DatagramSocket socketEntrada;

        /**
         * Constructor de la clase HiloEntrada.
         * @param sk Socket entrada.
         */
        public HiloEntrada(DatagramSocket sk) {
            this.socketEntrada = sk;
        }

        /**
         * Metodo para determinar si el paquete está corrupto.
         * @param paq Arreglo de bytes del paquete.
         * @return Devuelve -1 si es corrupto y sino el numero de ACK.
         */
        public int decodificarPaquete(byte[] paq) {
            byte[] checksumRecvBytes = copyOfRange(paq, 0, 8); //Arreglo de bytes del checksum recibido.
            byte[] numAckBytes = copyOfRange(paq, 8, 12); //Arreglo de bytes del numero de ACK recibido.
            CRC32 checksum = new CRC32();
            checksum.update(numAckBytes);
            byte[] checksumCalBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array(); //Arreglo de bytes del checksum calculado.
            if(Arrays.equals(checksumRecvBytes, checksumCalBytes)) {
                return ByteBuffer.wrap(numAckBytes).getInt();
            }
            else {
                return -1;
            }
        }

        public void run() {
            try {
                byte[] datosEntrada = new byte[12]; //Paquete ACK sin datos.
                DatagramPacket paqueteEntrada = new DatagramPacket(datosEntrada, datosEntrada.length);
                try {
                    while(!transferenciaCompleta) { //Mientras todavía hay paquetes por recibir por el receptor.
                        socketEntrada.receive(paqueteEntrada);
                        int numAck = decodificarPaquete(datosEntrada);
                        System.out.println("Emisor: ACK recibido: " + numAck);

                        if(numAck != -1) { //Si el ACK no es corrupto.
                            if(base == numAck+1) { //Si ACK es duplicado.
                                sem.acquire();
                                setTimer(false); //Finalice temporizador.
                                sigNumSecuencia = base; //Resetea el siguiente numero de secuencia.
                                sem.release();
                            }
                            else if(numAck == -2) { //Si es un ACK para terminar.
                                transferenciaCompleta = true;
                            }
                            else { //Si es un ACK normal.
                                base = numAck++; //Actualice el numero base.
                                sem.acquire();
                                if(base == sigNumSecuencia) { // Si no hay más paquetes para los cuáles no se ha recibido el ACK, finalice el temporizador.
                                    setTimer(false);
                                }
                                else {
                                    setTimer(true); //ACK de paquete recibido, reinicia el temporizador.
                                }
                                sem.release();
                            }
                        }
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                finally {
                    socketEntrada.close();
                    System.out.println("Emisor: Socket de entrada cerrado.");
                }
            }
            catch (Exception e) {
                e.printStackTrace();
                System.exit(-1);
            }
        }
    } //FIN DE CLASE HiloEntrada.

    //CLASE Timeout.
    public class Timeout extends TimerTask{
        public void run() {
            try {
                sem.acquire();
                System.out.println("Emisor: Timeout");
                sigNumSecuencia = base;	//Reinicia el siguiente numero de secuencia.
                hayMasDatos = true; //Reinicia la bandera de los datos.
                sem.release();
            } catch(InterruptedException e) {
                e.printStackTrace();
            }
        }
    } //FIN DE CLASE Timeout.

    /**
     * Constructor de la clase Emisor.
     */
    public EmisorUDP(int sk1PuertoDest, int sk2PuertoDest) {
        base = 0;
        sigNumSecuencia = 0;
        listaPaquetes = new Vector<byte[]>(tamVentana);
        transferenciaCompleta = false;
        DatagramSocket sk1, sk2;
        sem = new Semaphore(1);
        System.out.println("Emisor: socket1 puerto destino = " + sk1PuertoDest + ", socket2 puerto destino = " + sk2PuertoDest);

        try {
            sk1 = new DatagramSocket(); //Socket de salida.
            sk2 = new DatagramSocket(sk2PuertoDest); //Socket de entrada.

            HiloEntrada he = new HiloEntrada(sk2);
            HiloSalida hs = new HiloSalida(sk1, sk1PuertoDest, sk2PuertoDest);
            he.start();
            hs.start();
        }
        catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public static void main(String[] args) {

        // se pasan los parametros
        if (args.length != 2) {
            System.err.println("Uso: java EmisorUDP sk1PuertoDest, sk2PuertoDest");
            System.exit(-1);
        } else { new EmisorUDP(Integer.parseInt(args[0]), Integer.parseInt(args[1]));}

        /*String mensaje = JOptionPane.showInputDialog("Ingrese el mensaje que desea enviar:");
        try {
            DatagramSocket socket = new DatagramSocket();
            byte[] mensajeByte = mensaje.getBytes(); //Convierte el mensaje en una cadena de bytes.
            InetAddress host = InetAddress.getByName("127.0.0.1");
            int puerto = 9107;
            DatagramPacket paquete = new DatagramPacket(mensajeByte, mensaje.length(), host, puerto);  //Paquete a enviar.
            socket.send(paquete); //El socket envia el paquete.
        }
        catch(SocketException e) { //Manejo de excepcion para el datagram socket.
            e.printStackTrace();
        }
        catch (UnknownHostException e) { //Manejo de excepcion para el host.
            e.printStackTrace();
        }
        catch (IOException e) { //Manejo de excepcion para el datagram packet.
            e.printStackTrace();
        }*/
    }
}
