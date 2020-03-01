import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

public class Client {

    public static void main(String[] args) {
        try {
            int id = args.length == 0 ? 0 : Integer.parseInt(args[0]);
            Scanner scanner = new Scanner(new File("config.txt"));
            String[] address = new String[3];
            int idx = 0;
            while (scanner.hasNextLine()) {
                String[] arr = scanner.nextLine().split(" ");
                address[idx++] = arr[1];
            }
            scanner.close();
            Client client = new Client(id, address, new int[]{41230,41231,41232}, new String[]{"1.txt", "2.txt", "3.txt", "4.txt"});
            client.run();
            client.shutDown();
        }  catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
    private int id;
    private List<Socket> serverList;
    private HashMap<Socket, Scanner> scannerMap;
    private String[] filenameArr;
    private int timestamp;
    private Gson gson;
    Client(int id, String[] ipArr, int[] portArr, String[] fileArr) {
        this.id = id;
        serverList = new ArrayList<>();
        scannerMap = new HashMap<>();
        filenameArr = fileArr.clone();
        timestamp = 0;
        gson = new Gson();
        DebugHelper.Log(DebugHelper.Level.INFO, "client " + id + " starts at time: " + timestamp);
        //connect to servers
        while (serverList.size() < ipArr.length) {
            for (int i = 0; i < ipArr.length; i++) {
                try {
                    Socket socket = new Socket(ipArr[i], portArr[i]);
                    serverList.add(socket);
                    scannerMap.put(socket, new Scanner(socket.getInputStream()));
                    DebugHelper.Log(DebugHelper.Level.INFO, "Connect to Server:" + socket);
                }
                catch (Exception e) {
                    DebugHelper.Log(DebugHelper.Level.DEBUG, "waiting for connection:" + ipArr[i] + ":" + portArr[i]);
                }
            }
        }
    }

    private void run() throws InterruptedException, IOException {
        //run 100 loop, in each loop, send an append request to a random server for appending a random file and wait for response
        for (int i = 0; i < 100; i++) {
            timestamp++;
            Thread.sleep((long) (Math.random() * 1000));
            int randServerId = (int) (Math.random() * serverList.size());
            Socket socket = serverList.get(randServerId);
            int randFileId = (int) (Math.random() * filenameArr.length);
            Message msg = new Message(Message.Type.APPEND, "client "+id+", message "+i+"\n", filenameArr[randFileId], timestamp, id, randServerId, Message.Result.SUCCESS);
            socket.getOutputStream().write((msg.toString() + "\n").getBytes());
            socket.getOutputStream().flush();
            Scanner scanner = scannerMap.get(socket);
            DebugHelper.Log(DebugHelper.Level.DEBUG, "Send:" + socket + msg.toString());
            DebugHelper.Log(DebugHelper.Level.INFO, "client "+id+" requests: \"client "+id+" message #"+i+" -- server "+randServerId+"\" for file #"+randFileId+" at time: "+timestamp);
            while (scanner.hasNextLine()) {
                processMsg(gson.fromJson(scanner.nextLine(), Message.class));
                break;
            }
        }
    }

    private void processMsg(Message msg) {
        DebugHelper.Log(DebugHelper.Level.DEBUG, msg.toString());
        DebugHelper.Log(DebugHelper.Level.INFO, "client "+id+" receives: a "+(msg.result == Message.Result.SUCCESS ?"successful ack":"failure")+" from server "+msg.from);
        this.timestamp = Math.max(this.timestamp, msg.timestamp) + 1;
    }

    private void shutDown() throws IOException {
        for (Socket socket : serverList) {
            socket.shutdownOutput();
        }
        DebugHelper.Log(DebugHelper.Level.INFO, "client "+id+" gracefully shutdown.");
    }
}
