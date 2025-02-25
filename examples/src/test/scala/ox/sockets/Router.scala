package ox.sockets

import org.slf4j.LoggerFactory
import ox.syntax.{forever, fork}
import ox.{Fork, scoped, Ox}

import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue}
import scala.annotation.tailrec
import scala.util.control.NonFatal

object Router:
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val Timeout = 1000L

  private sealed trait RouterMessage
  private case class Connected(socket: ConnectedSocket) extends RouterMessage
  private case class Received(socket: ConnectedSocket, msg: String) extends RouterMessage
  private case class Terminated(socket: ConnectedSocket) extends RouterMessage

  def router(socket: Socket): Unit = scoped {
    case class ConnectedSocketData(sendFork: Fork[Unit], receiveFork: Fork[Unit], sendQueue: BlockingQueue[String])

    @tailrec
    def handleMessage(queue: BlockingQueue[RouterMessage], socketSendQueues: Map[ConnectedSocket, ConnectedSocketData]): Unit =
      queue.take match
        case Connected(connectedSocket) =>
          val sendQueue = new ArrayBlockingQueue[String](32)
          val sendFork = clientSend(connectedSocket, queue, sendQueue)
          val receiveFork = clientReceive(connectedSocket, queue)
          handleMessage(queue, socketSendQueues + (connectedSocket -> ConnectedSocketData(sendFork, receiveFork, sendQueue)))

        case Terminated(connectedSocket) =>
          socketSendQueues.get(connectedSocket) match
            case None => ()
            case Some(ConnectedSocketData(sendFork, receiveFork, _)) =>
              sendFork.cancel()
              receiveFork.cancel()

          handleMessage(queue, socketSendQueues - connectedSocket)

        case Received(receivedFrom, msg) =>
          socketSendQueues.foreach { case (connectedSocket, ConnectedSocketData(_, _, sendQueue)) =>
            if connectedSocket != receivedFrom then sendQueue.put(msg)
          }
          handleMessage(queue, socketSendQueues)

    val queue = new ArrayBlockingQueue[RouterMessage](32)
    socketAccept(socket, queue)
    handleMessage(queue, Map())
  }

  private def socketAccept(socket: Socket, parent: BlockingQueue[RouterMessage])(using Ox): Fork[Unit] = {
    try
      val connectedSocket = socket.accept(Timeout)
      if connectedSocket != null then parent.put(Connected(connectedSocket))
    catch case NonFatal(e) => logger.error(s"Exception when listening on a socket", e)
  }.forever.fork

  private def clientSend(socket: ConnectedSocket, parent: BlockingQueue[RouterMessage], sendQueue: BlockingQueue[String])(using
      Ox
  ): Fork[Unit] = {
    val msg = sendQueue.take
    try socket.send(msg)
    catch
      case e: InterruptedException => throw e
      case e: SocketTerminatedException =>
        parent.put(Terminated(socket))
        throw e
      case e => logger.error(s"Exception when sending to socket", e)
  }.forever.fork

  private def clientReceive(socket: ConnectedSocket, parent: BlockingQueue[RouterMessage])(using Ox): Fork[Unit] = {
    try
      val msg = socket.receive(Timeout)
      if msg != null then parent.put(Received(socket, msg))
    catch
      case e: InterruptedException => throw e
      case e: SocketTerminatedException =>
        parent.put(Terminated(socket))
        throw e
      case e => logger.error("Exception when receiving from a socket", e)
  }.forever.fork
