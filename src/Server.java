import com.google.gson.Gson;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;

public class Server {
    public static void main(String[] args) throws Exception {
        int id = args.length == 0 ? 0 : Integer.parseInt(args[0]);
        Scanner scanner = new Scanner(new File("config.txt"));
        String[] address = new String[3];
        int idx = 0;
        while (scanner.hasNextLine()) {
            String[] arr = scanner.nextLine().split(" ");
            address[idx++] = arr[1];
        }
        scanner.close();

        Server server = new Server(id, address, new int[]{41230,41231,41232}, new String[]{"1.txt", "2.txt", "3.txt", "4.txt"});
        server.run();
    }

    private int id;
    private int timestamp;
    private HashMap<String, PriorityBlockingQueue<Message>> requestQueue;
    ExecutorService pool;
    ServerSocket listener;
    //each file has a waiting list, a server can have at most one request for each file
    //ConcurrentHashMap<filename, ConcurrentHashMap<fromPort, Socket>>
    private ConcurrentHashMap<String, ConcurrentHashMap<Integer, Socket>> replyWaitList;
    private Map<Integer, Socket> otherServers;//key: server id
    private ConcurrentHashMap<Integer, Socket> clients;//key: client port
    private ConcurrentHashMap<Integer, Integer> clientId2Port;//key: client id, value: client port
    private PriorityBlockingQueue<Message> clientRequestBuffer;
    Server(int id, String[] ipArr, int[] portArr, String[] filenameArr) throws IOException {
        this.id = id;
        this.timestamp = 0;
        this.requestQueue = new HashMap<>();
        this.clients = new ConcurrentHashMap<>();
        this.clientId2Port = new ConcurrentHashMap<>();
        this.clientRequestBuffer = new PriorityBlockingQueue<>();
        this.pool = Executors.newFixedThreadPool(20);
        this.listener = new ServerSocket(portArr[id]);
        this.replyWaitList = new ConcurrentHashMap<>();
        for (String name : filenameArr) {
            this.replyWaitList.put(name, new ConcurrentHashMap<>());
            this.requestQueue.put(name, new PriorityBlockingQueue<>());
            File file = new File(name);
            PrintWriter pw = new PrintWriter(file);//truncate file if exists
        }
        //loop until connections with all the other servers are established
        this.otherServers = new HashMap<>();
        DebugHelper.Log(DebugHelper.Level.INFO, "server " + id + " starts at time: " + timestamp);
        while (this.otherServers.size() < ipArr.length - 1) {
            for (int i = 0; i < portArr.length; i++) {
                if (i != id && !this.otherServers.containsKey(i)) {
                    try {
                        Socket socket = new Socket(ipArr[i], portArr[i]);
                        this.otherServers.put(i, socket);
                        DebugHelper.Log(DebugHelper.Level.INFO, "Connect to Server:" + socket);
                    }
                    catch (Exception e) {
                        //waiting for the other servers
                        DebugHelper.Log(DebugHelper.Level.DEBUG, "waiting for connection:" + ipArr[i] + ":" + portArr[i]);
                    }
                }
            }
        }
        DebugHelper.Log(DebugHelper.Level.INFO, "Initialized");
    }

    private void run() throws IOException {
        while (true) {
            pool.execute(new ServerListener(this, this.listener.accept()));
        }
    }
    //restore client socket for replying
    private void onClientConnected(Socket socket) {
        this.clients.put(socket.getPort(), socket);
    }
    //remove client socket when disconnected
    private void onClientDisconnected(Socket socket) {
        this.clients.remove(socket.getPort());
    }
    //if msg is sending from myself
    private boolean isLocalMsg(Message msg) {
        return this.id == msg.from;
    }
    //update logic clock
    private void updateTimestamp(int receiveTimestamp) {
        synchronized (this) {
            this.timestamp = Math.max(this.timestamp, receiveTimestamp) + 1;
        }
    }
    //send message in json
    private void send(Message msg, Socket socket) throws IOException {
        msg.from = this.id;
        msg.timestamp = this.timestamp;
        //log
        DebugHelper.Log(DebugHelper.Level.DEBUG, "Send:" + socket + msg.toString());
        if (msg.type == Message.Type.RESPONSE)
            DebugHelper.Log(DebugHelper.Level.INFO, "server " + id + " sends a successful ack to client " + msg.to);
        else
            DebugHelper.Log(DebugHelper.Level.INFO, "server "+id+" sends a "+msg.type.toString()+" to server "+msg.to+" for appending \""+msg.content+"\" in "+msg.filename + " at time:"+timestamp);
        //send msg
        socket.getOutputStream().write((msg.toString() + "\n").getBytes());
        socket.getOutputStream().flush();
    }
    //broadcast to all the other servers
    private void broadcastToOtherServers(Message msg) throws IOException {
        if (msg.type == Message.Type.REQUEST)//request message need to reply
            replyWaitList.put(msg.filename, new ConcurrentHashMap<>(otherServers));
        for (Map.Entry<Integer, Socket> entry :otherServers.entrySet()) {
            msg.from = this.id;
            msg.to = entry.getKey();
            send(msg, entry.getValue());
        }
    }
    //this function will be called when receive reply message or one client request has just been processed
    //if there is no other request for the same file (critical section) and there are buffered client requests, then
    //start a enter critical section request, broadcast to all the other servers, and place the request on request queue
    private void tryProcessClientRequest(String filename) throws IOException {
        if (replyWaitList.get(filename).isEmpty() && !this.clientRequestBuffer.isEmpty()) {
            Message appendMsg = this.clientRequestBuffer.poll();
            Message requestMsg = appendMsg.clone();
            requestMsg.type = Message.Type.REQUEST;
            broadcastToOtherServers(requestMsg);
            //add into queue
            appendMsg.to = appendMsg.from;
            appendMsg.from = this.id;
            appendMsg.timestamp = this.timestamp;
            requestQueue.get(filename).add(appendMsg);
        }
    }
    //on received client append request
    //place the message on client request buffer and check if it can be process right now
    private void handleClientAppendRequest(Message msg, Socket socket) throws IOException {
        clientId2Port.put(msg.from, socket.getPort());//update client id->port map
        clientRequestBuffer.add(msg);
        tryProcessClientRequest(msg.filename);
    }
    //request message from other servers
    private void handleRequest(Message msg) throws IOException {
        //add into queue
        requestQueue.get(msg.filename).add(msg);
        //reply to the sender
        Message replyMsg = msg.clone();
        replyMsg.type = Message.Type.REPLY;
        replyMsg.to = replyMsg.from;
        send(replyMsg, otherServers.get(msg.from));
    }
    //if all the other servers has replied and the first message in the request queue is mine, then enter critical section
    private void tryExecuteCriticalSection(String filename) throws IOException {
        if (replyWaitList.get(filename).isEmpty() && !requestQueue.get(filename).isEmpty() && isLocalMsg(requestQueue.get(filename).peek())) {
            //response to client
            Message clientRequest = requestQueue.get(filename).poll();
            clientRequest.type = Message.Type.RESPONSE;
            send(clientRequest, clients.get(clientId2Port.get(clientRequest.to)));
            //append local file
            appendFile(filename, clientRequest.content);
            //broadcast release message to other servers
            Message releaseMsg = clientRequest.clone();
            releaseMsg.type = Message.Type.RELEASE;
            broadcastToOtherServers(releaseMsg);
            //process next append message in client request buffer
            tryProcessClientRequest(filename);
        }
    }

    //reply message from other servers
    private void handleReply(Message msg) throws IOException {
        replyWaitList.get(msg.filename).remove(msg.from);
        //try execute critical section
        tryExecuteCriticalSection(msg.filename);
    }
    //release message from other servers
    private void handleRelease(Message msg) throws IOException {
        //remove message from request queue
        requestQueue.get(msg.filename).remove();
        //append local file
        appendFile(msg.filename, msg.content);
        //try execute critical section since head of the request queue has been removed
        tryExecuteCriticalSection(msg.filename);
    }
    //append local file
    private void appendFile(String filename, String content) throws IOException {
        FileWriter myWriter = new FileWriter(filename, true);
        myWriter.append(content);
        myWriter.close();
    }
    //server listener thread
    private static class ServerListener implements Runnable {
        Server server;
        Socket socket;
        Gson gson;
        ServerListener(Server server, Socket socket) {
            this.server = server;
            this.socket = socket;
        }

        private void processMsg(Message msg) throws IOException {
            DebugHelper.Log(DebugHelper.Level.DEBUG, "Receive:" + socket  + msg.toString());
            //update logic clock
            this.server.updateTimestamp(msg.timestamp);
            switch (msg.type) {
                case REQUEST:
                    this.server.handleRequest(msg);
                    break;
                case REPLY:
                    this.server.handleReply(msg);
                    break;
                case RELEASE:
                    this.server.handleRelease(msg);
                    break;
                case APPEND:
                    this.server.handleClientAppendRequest(msg, socket);
                    break;
            }
        }

        @Override
        public void run() {
            DebugHelper.Log(DebugHelper.Level.DEBUG, "Connected: " + this.socket);
            try {
                this.server.onClientConnected(this.socket);
                Scanner in = new Scanner(this.socket.getInputStream());
                gson = new Gson();
                while (in.hasNextLine()) {
                    Message msg = gson.fromJson(in.nextLine(), Message.class);
                    synchronized (this.server) {
                        processMsg(msg);
                    }
                }
            } catch (Exception e) {
                DebugHelper.Log(DebugHelper.Level.DEBUG, "ServerListener Exception " + e.getMessage() + ":" + this.socket);
                e.printStackTrace();
            } finally {
                try {
                    this.server.onClientDisconnected(this.socket);
                    this.socket.close();
                } catch (IOException e) {
                    DebugHelper.Log(DebugHelper.Level.DEBUG, "Socket Close Exception " + e.getMessage() + ":" + this.socket);
                }
                DebugHelper.Log(DebugHelper.Level.DEBUG, "Closed: " + this.socket);
            }
        }
    }
}
