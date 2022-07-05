import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

import static java.lang.Thread.*;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

public class TCPServer {

    private static final String EXIT_COMMAND = "exit";
    private static final String MESSAGE_TEMPLATE = "Message %d for client %s:%d.";
    private static final String CLIENT_CONNECTED_TEMPLATE = "New client %s with port %s was connected.";
    private static final String SERVER_OVERLOADED = "You can't connect to the server because it's overloaded.";
    private static final String CANT_CONNECT_CLIENT_TEMPLATE =
            "Can't connect client %s with port %s. It has already been connected";
    private static final String MESSAGE_SEND_WITH_DELAY_TEMPLATE = "Messages will be sent with delay %d ms.";
    private static final String NO_CLIENT_CONNECTED = "No client was connected on current iteration.";
    private static final int SO_TIMEOUT = 2000;
    private static Random random = new Random();
    private final ArrayBlockingQueue<Socket> clientConnectionsQueue = new ArrayBlockingQueue<>(10);
    private final Map<String, ClientHandler> clientHandlersPool = new HashMap<>(); // key is hostname + port num String
    private volatile CountDownLatch clientHandlersReadyCDLatch;
    private int clientHandlersNum;
    private volatile boolean isWaitingFlag;

    public TCPServer() {
        this.clientHandlersNum = 0;
        this.isWaitingFlag = false;
    }

    public static void main(String[] args) {
        final TCPServer server = new TCPServer();
        server.startServer();
    }

    public void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(4999)) {
            serverSocket.setReuseAddress(true);
            String curClientHostName;
            int curClientPort;
            String curClientHandlerKey;
            int messageSendDelay;
            boolean clientWasConnectedFlag;
            Socket clientConnectionSocket;
            ClientConnectionsHandler clientConnectionsHandler = new ClientConnectionsHandler(serverSocket);
            clientConnectionsHandler.start();
            while (true) {
                Thread.sleep(SO_TIMEOUT);
                clientConnectionSocket = clientConnectionsQueue.poll();
                if (clientConnectionSocket != null) {
                    clientWasConnectedFlag = true;
                } else {
                    clientWasConnectedFlag = false;
                }

                if (clientWasConnectedFlag) {
                    curClientHostName = clientConnectionSocket.getInetAddress().getHostName();
                    curClientPort = clientConnectionSocket.getPort();
                    curClientHandlerKey = curClientHostName + curClientPort;
                    if (!clientHandlersPool.containsKey(curClientHandlerKey)) {
                        clientHandlersNum++;
                        clientHandlersReadyCDLatch = new CountDownLatch(clientHandlersNum);
                        ClientHandler clientHandler = new ClientHandler(clientConnectionSocket);
                        clientHandlersPool.put(curClientHandlerKey, clientHandler);
                        isWaitingFlag = true;
                        System.out.println(String.format(CLIENT_CONNECTED_TEMPLATE, curClientHostName, curClientPort));
                        clientHandler.start();
                    } else {
                        clientHandlersReadyCDLatch = new CountDownLatch(clientHandlersNum);
                        isWaitingFlag = true;
                        System.out.println(String.format(CANT_CONNECT_CLIENT_TEMPLATE, curClientHostName, curClientPort));
                    }
                } else {
                    clientHandlersReadyCDLatch = new CountDownLatch(clientHandlersNum);
                    isWaitingFlag = true;
                    System.out.println(NO_CLIENT_CONNECTED);
                }
                clientHandlersReadyCDLatch.await();
                messageSendDelay = random.nextInt(1000);
                sleep(messageSendDelay);
                System.out.println(String.format(MESSAGE_SEND_WITH_DELAY_TEMPLATE, messageSendDelay));
                clientHandlersPool.keySet().stream().forEach(curKey ->
                        clientHandlersPool.get(curKey).releaseSendMessageCDLatch());
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void interruptAllClientHandlers() {
        for (ClientHandler curClientHandler : clientHandlersPool.values()) {
            curClientHandler.interrupt();
        }
    }

    private class ClientConnectionsHandler extends Thread {

        private final ServerSocket serverSocket;
        private boolean isMessageSent;

        public ClientConnectionsHandler(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            isMessageSent = false;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    Socket clientConnectionSocket = serverSocket.accept();
                    boolean isAddedSuccessfully = clientConnectionsQueue.add(clientConnectionSocket);
                    if (!isAddedSuccessfully) {
                        try (PrintWriter outPrintWriter = new PrintWriter(clientConnectionSocket.getOutputStream());
                             DataInputStream inBoolStream = new DataInputStream(clientConnectionSocket.getInputStream())) {
                            outPrintWriter.println(SERVER_OVERLOADED);
                            outPrintWriter.flush();
                            while (true) {
                                try {
                                    isMessageSent = inBoolStream.readBoolean();
                                    if (isMessageSent) {
                                        break;
                                    }
                                } catch (EOFException e) {
                                    continue;
                                }
                            }
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class ClientHandler extends Thread {
        private final Socket clientSocket;
        private int messageCounter;
        private boolean isMessageSent;
        private volatile CountDownLatch sendMessageCDLatch;

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
            this.messageCounter = 0;
            this.isMessageSent = false;
            this.sendMessageCDLatch = new CountDownLatch(1);
        }

        public void releaseSendMessageCDLatch() {
            sendMessageCDLatch.countDown();
        }

        @Override
        public void run() {
            try (PrintWriter outPrintWriter = new PrintWriter(clientSocket.getOutputStream());
                 DataInputStream inBoolStream = new DataInputStream(clientSocket.getInputStream())) {
                String clientHostName = clientSocket.getInetAddress().getHostName();
                int clientPort = clientSocket.getPort();
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }

                    TCPServer.this.clientHandlersReadyCDLatch.countDown();
                    //System.out.println("CHRLatchReleased");
                    sendMessageCDLatch.await();

                    outPrintWriter.println(String.format(MESSAGE_TEMPLATE, messageCounter, clientHostName, clientPort));
                    outPrintWriter.flush();
                    System.out.println("Message sent.");
                    while (true) {
                        try {
                            isMessageSent = inBoolStream.readBoolean();
                            if (isMessageSent) {
                                break;
                            }
                        } catch (EOFException e) {
                            continue;
                        }
                    }
                    messageCounter++;
                    isMessageSent = false;
                    isWaitingFlag = false;

                    sendMessageCDLatch = new CountDownLatch(1);
                    //System.out.println("isWaitingFlag = " + isWaitingFlag);
                    while (!isWaitingFlag) ;
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}
