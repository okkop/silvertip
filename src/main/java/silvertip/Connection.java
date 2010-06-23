package silvertip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class Connection {
  public interface Callback {
    void messages(Connection connection, Iterator<Message> messages);
    void idle(Connection connection);
  }

  private ByteBuffer rxBuffer = ByteBuffer.allocate(4096);
  private SocketChannel channel;
  private MessageParser parser;
  private boolean closed;
  private long idleMsec;

  public static Connection connect(InetSocketAddress address, long idleMsec, MessageParser parser) throws IOException {
    SocketChannel channel = SocketChannel.open();
    channel.connect(address);
    channel.configureBlocking(false);
    return new Connection(channel, idleMsec, parser);
  }

  public Connection(SocketChannel channel, long idleMsec, MessageParser parser) {
    this.channel = channel;
    this.idleMsec = idleMsec;
    this.parser = parser;
  }

  public void close() {
    this.closed = true;
  }

  public void send(Message message) {
    try {
      channel.write(message.toByteBuffer());
    } catch (IOException e) {
      close();
    }
  }

  public void wait(Callback callback) throws IOException {
    Selector selector = Selector.open();
    SelectionKey selectorKey = channel.register(selector, SelectionKey.OP_READ);
    while (!closed) {
      int numKeys = selector.select(idleMsec);

      if (numKeys == 0) {
        callback.idle(this);
        continue;
      }

      Set<SelectionKey> selectedKeys = selector.selectedKeys();
      Iterator<SelectionKey> it = selectedKeys.iterator();
      while (it.hasNext()) {
        SelectionKey key = it.next();
        if (key.isReadable())
          read(callback, key);
        it.remove();
      }
    }
    close(selectorKey);
  }

  private void read(Callback callback, SelectionKey key) throws IOException {
    SocketChannel sc = (SocketChannel) key.channel();
    if (sc.isOpen()) {
      int len;
      try {
        len = sc.read(rxBuffer);
      } catch (IOException e) {
        len = -1;
      }
      if (len > 0) {
        Iterator<Message> messages = parse();
        if (messages.hasNext()) {
          callback.messages(this, messages);
        }
      } else if (len < 0) {
        close();
      }
    }
  }

  private Iterator<Message> parse() throws IOException {
    rxBuffer.flip();
    List<Message> result = new ArrayList<Message>();
    while (rxBuffer.hasRemaining()) {
      rxBuffer.mark();
      try {
        result.add(parser.parse(rxBuffer));
      } catch (PartialMessageException e) {
        rxBuffer.reset();
        break;
      } catch (GarbledMessageException e) {
        rxBuffer.reset();
        byte[] data = new byte[rxBuffer.limit() - rxBuffer.position()];
        rxBuffer.get(data);
        result.add(new Message(data));
        break;
      }
    }
    rxBuffer.compact();
    return result.iterator();
  }

  private static void close(SelectionKey key) throws IOException {
    SocketChannel sc = (SocketChannel) key.channel();
    if (sc.isOpen()) {
      sc.close();
      key.selector().wakeup();
      key.attach(null);
    }
  }
}
