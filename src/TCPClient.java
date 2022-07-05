import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.NoSuchElementException;
import java.util.Scanner;

public class TCPClient {

    private static final String EXIT_COMMAND = "exit";
    private static final String MESSAGE_RECEIVED_TEMPLATE = "The following message from a server was received: %s";
    private static final String WAITING_FOR_NEW_MESSAGE = "Waiting for the new message.";

    public static void main(String[] args) {
        String inputMessage;
        try (Socket clientSocket = new Socket("127.0.0.1", 4999);
             Scanner consoleScanner = new Scanner(System.in);
             InputStreamReader serverInStreamReader = new InputStreamReader(clientSocket.getInputStream());
             DataOutputStream serverDataOutputStream = new DataOutputStream(clientSocket.getOutputStream());
             Scanner serverInStreamScanner = new Scanner(serverInStreamReader)) {
            while (true) {
                System.out.println(WAITING_FOR_NEW_MESSAGE);
                while (true) {
                    try {
                        inputMessage = serverInStreamScanner.nextLine();
                        if (inputMessage != null) {
                            break;
                        }
                    } catch (NoSuchElementException e) {
                        continue;
                    }
                }
                System.out.println(String.format(MESSAGE_RECEIVED_TEMPLATE, inputMessage));
                serverDataOutputStream.writeBoolean(true);
                serverDataOutputStream.flush();
                /*if(consoleScanner.nextLine().equals(EXIT_COMMAND)) {
                    break;
                }*/
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
