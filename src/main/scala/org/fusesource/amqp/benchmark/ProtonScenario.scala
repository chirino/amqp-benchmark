/**
 * Copyright (C) 2009-2012 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.amqp.benchmark

import org.fusesource.hawtdispatch._
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.fusesource.hawtbuf.Buffer._
import org.apache.activemq.apollo.amqp.hawtdispatch.api._
import org.apache.qpid.proton.`type`.messaging.{ApplicationProperties, Target, Source}
import org.apache.qpid.proton.`type`.transport.DeliveryState
import org.apache.qpid.proton.engine.impl.ProtocolTracer
import org.apache.qpid.proton.framing.TransportFrame

object ProtonScenario {
  def main(args: Array[String]) {
    val scenario = new ProtonScenario
    def r(p:Int, d:Int, c:Int) = { scenario.producers = p; scenario.destination_count = d; scenario.consumers = c; scenario.run}
    scenario.host = "localhost"
//    scenario.port = 61613
    scenario.display_errors = true
//    scenario.login = Some("admin")
//    scenario.passcode = Some("password")
    scenario.destination_type = "queue";
    scenario.producer_qos = "AT_MOST_ONCE"
    scenario.consumer_qos = "AT_MOST_ONCE"
//    scenario.producer_qos = "AT_LEAST_ONCE"
//    scenario.consumer_qos = "AT_LEAST_ONCE"
//    scenario.consumer_prefetch = 1
    scenario.message_size = 20
    scenario.trace = false
    r(1,1,1)
  }
}

/**
 * <p>
 * Simulates load on the an AMQP sever using the
 * Proton based client lib.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class ProtonScenario extends Scenario {

  def createProducer(i:Int) = {
    new ProducerClient(i)
  }
  def createConsumer(i:Int) = {
    new ConsumerClient(i)
  }
  var trace = false

  trait FuseSourceClient extends Client {

    var connection:AmqpConnection = _
    val queue = createQueue()

    var message_counter=0L
    var reconnect_delay = 0L
    def id:Int

    sealed trait State

    case class INIT() extends State


    case class CONNECTING(host: String, port: Int, on_complete: ()=>Unit) extends State {

      def connect() = {
        val options = new AmqpConnectOptions();
        options.setDispatchQueue(queue)
        options.setHost(host, port)
        for ( x <- login ) { options.setUser( x )}
        for ( x <- passcode ) { options.setPassword( x )}
        connection = AmqpConnection.connect(options)
        if( trace ) {
          connection.setProtocolTracer(new ProtocolTracer() {
            def receivedFrame(transportFrame: TransportFrame) = {
               println("%11.11s | RECV | %s".format(name, transportFrame.getBody()));
             }
             def sentFrame(transportFrame: TransportFrame) = {
               println("%11.11s | SEND | %s".format(name, transportFrame.getBody()));
             }
          });
        }
        connection.onConnected(new Callback[Void] {
          def onSuccess(value: Void) {
            if ( CONNECTING.this == state ) {
              if(done.get) {
                close
              } else {
                state = CONNECTED()
                on_complete()
              }
            }
          }
          def onFailure(value: Throwable) {
            on_failure(value)
          }
        })
        connection.onTransportFailure(new Callback[Throwable] {
          def onFailure(value: Throwable) = on_failure(value)
          def onSuccess(value: Throwable) = on_failure(value)
        })

        // Times out the connect after 5 seconds...
        queue.after(5, TimeUnit.SECONDS) {
          if ( this == state ) {
            on_failure(new Exception("Connect timed out"))
          }
        }
      }

      // We may need to delay the connection attempt.
      if( reconnect_delay==0 ) {
        connect
      } else {
        queue.after(1, TimeUnit.SECONDS) {
          if ( this == state ) {
            reconnect_delay=0
            connect
          }
        }
      }

      def close() = {
        if( connection.getTransportState == CONNECTED ) {
          connection.close
          state = CLOSING()
        } else {
          state = DISCONNECTED()
        }
      }

      def on_failure(e:Throwable) = {
        if( display_errors ) {
          e.printStackTrace
        }
        error_counter.incrementAndGet
        reconnect_delay = 1000
        close
      }

    }

    case class CONNECTED() extends State {

      def close() = {
        if( connection.getTransportState == CONNECTED ) {
          connection.close
          state = CLOSING()
        } else {
          state = DISCONNECTED()
        }
      }

      def on_failure(e:Throwable) = {
        if( display_errors ) {
          e.printStackTrace
        }
        error_counter.incrementAndGet
        reconnect_delay = 1000
        close
      }

    }
    case class CLOSING() extends State

    case class DISCONNECTED() extends State {
      queue {
        if( state==this ){
          if( done.get ) {
            has_shutdown.countDown
          } else {
            reconnect_action
          }
        }
      }
    }


    def on_close:Unit = {
      if( done.get ) {
        has_shutdown.countDown
      } else {
        if( connection.getTransportFailure()!=null ) {
          state match {
            case x:CONNECTING => x.on_failure(connection.getTransportFailure)
            case x:CONNECTED => x.on_failure(connection.getTransportFailure)
            case _ =>
          }
        } else {
          state = DISCONNECTED()
        }
      }
    }


    var state:State = INIT()

    val has_shutdown = new CountDownLatch(1)
    def reconnect_action:Unit

    def start = queue {
      state = DISCONNECTED()
    }

    def queue_check = assert(getCurrentQueue == queue)

    def open(host: String, port: Int)(on_complete: =>Unit) = {
      queue_check
      assert ( state.isInstanceOf[DISCONNECTED] )
      state = CONNECTING(host, port, ()=>on_complete)
    }

    def close() = {
      queue_check
      state match {
        case x:CONNECTING => x.close
        case x:CONNECTED => x.close
        case _ =>
      }
    }

    def shutdown = {
      assert(done.get)
      queue {
        close
      }
      has_shutdown.await()
    }

    def connect(proc: =>Unit) = {
//      println(name+" connecting..")
      queue_check
      if( !done.get ) {
        open(host, port) {
//          println(name+" connected..")
          proc
        }
      }
    }

    def name:String
  }

  class ConsumerClient(val id: Int) extends FuseSourceClient {
    val name: String = "consumer " + id
    queue.setLabel(name)

    var session:AmqpSession = _
    var receiver:AmqpReceiver = _
    val qos = QoS.valueOf(producer_qos)

    override def reconnect_action = {
      connect {
        session = connection.createSession
        val link_name = destination(id)+" => "+name
        val source = new Source
        source.setAddress(destination(id))
        receiver = session.createReceiver(source, qos, consumer_prefetch, link_name)
        receiver.resume()
        receiver.setDeliveryListener(new AmqpDeliveryListener(){
          var sleeping = false
          def onMessageDelivery(delivery: MessageDelivery) = {
            if( sleeping ) {
              throw new IllegalStateException("Got delivery while suspended.")
            } else {
              val c_sleep = consumer_sleep
              if( c_sleep != 0 ) {
                receiver.suspend()
                sleeping = true
                queue.after(math.abs(c_sleep), TimeUnit.MILLISECONDS) {
                  receiver.resume()
                  sleeping = false
                  consumer_counter.incrementAndGet()
                  delivery.settle()
                }
              } else {
                consumer_counter.incrementAndGet()
                delivery.settle()
              }
            }
          }
        })
      }
    }
  }

  class ProducerClient(val id: Int) extends FuseSourceClient {

    val name: String = "producer " + id
    queue.setLabel(name)

    var session:AmqpSession = _
    var sender:AmqpSender = _
    val data = ascii(body(name));
    val qos = QoS.valueOf(producer_qos)

    override def reconnect_action = {
      connect {
        session = connection.createSession
        val link_name = name+" => "+destination(id)
        val target = new Target
        target.setAddress(destination(id))
        sender = session.createSender(target, qos, link_name)
        send_next
      }
    }


    def send_next:Unit = {
      val message = session.createBinaryMessage(data.data, data.offset, data.length);
      message.setDurable(durable)

      def send_completed:Unit = {
        message_counter += 1
        producer_counter.incrementAndGet()

        def continue_sending = {
          if(messages_per_connection > 0 && message_counter >= messages_per_connection  ) {
            message_counter = 0
            close
          } else {
            send_next
          }
        }

        val p_sleep = producer_sleep
        if(p_sleep != 0) {
          queue.after(math.abs(p_sleep), TimeUnit.MILLISECONDS) {
            continue_sending
          }
        } else {
          continue_sending
        }
      }

      def send:Unit = if( !done.get) {
        val md = sender.send(message)
        md.onSettle(new Callback[DeliveryState] {
          def onFailure(value: Throwable) {
            value.printStackTrace()
            close
          }
          def onSuccess(value: DeliveryState) {
            send_completed
          }
        })
      }

      if( !done.get ) {
        send
      } else {
        close
      }
    }

  }

  def body(name:String) = {
    val buffer = new StringBuffer(message_size)
    buffer.append("Message from " + name+"\n")
    for( i <- buffer.length to message_size ) {
      buffer.append(('a'+(i%26)).toChar)
    }
    var rc = buffer.toString
    if( rc.length > message_size ) {
      rc.substring(0, message_size)
    } else {
      rc
    }
  }



}
