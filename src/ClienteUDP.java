import javax.swing.*;
import java.io.IOException;
import java.net.*;

public class ClienteUDP {

    /**
     * Metodo para enviar mensajes desde el cliente.
     * @param args
     * @throws UnknownHostException
     */
    public static void main(String[] args) throws UnknownHostException {
        String mensaje = JOptionPane.showInputDialog("Ingrese el mensaje que desea enviar:");
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
        }
    }
}
