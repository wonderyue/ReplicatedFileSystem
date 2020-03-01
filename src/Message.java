import com.google.gson.Gson;

public class Message implements Comparable<Message> {
    public enum Type {
        //server-server message
        REQUEST,
        REPLY,
        RELEASE,
        //client-server message
        APPEND,
        RESPONSE
    }

    public enum Result {
        SUCCESS,
        FAIL
    }

    Type type;
    String content;
    String filename;
    int timestamp;
    int from;
    int to;
    Result result;
    Message(Type type, String content, String filename, int timestamp, int from, int to, Result result) {
        this.type = type;
        this.filename = filename;
        this.content = content;
        this.timestamp = timestamp;
        this.from = from;
        this.to = to;
        this.result = result;
    }

    @Override
    public int compareTo(Message o) {
        return this.timestamp == o.timestamp ? this.from - o.from : this.timestamp - o.timestamp;
    }
    private static Gson gson;
    @Override
    public String toString() {
        if (gson == null)
            gson = new Gson();
        return gson.toJson(this);
    }

    @Override
    public Message clone() {
        return new Message(this.type, this.content, this.filename, this.timestamp, this.from, this.to, this.result);
    }
}
