/**
 * Copyright (C) 2009-2011 the original author or authors.
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

import scala.collection.mutable.HashMap
import scala.xml.{XML, NodeSeq}
import scala.util.control.Exception.catching
import scala.util.Random

import java.io.{PrintStream, FileOutputStream, File}
import collection.JavaConversions
import java.lang.{String, Class}

import org.apache.felix.gogo.commands.basic.DefaultActionPreparator
import org.apache.felix.gogo.commands.{CommandException, Action, Option => option, Argument => argument, Command => command}

import sun.misc.Signal;
import sun.misc.SignalHandler
import org.codehaus.jackson.map.ObjectMapper
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import org.osgi.service.command.CommandSession
;

object Benchmark {
  def main(args: Array[String]):Unit = {
    val session = new CommandSession {
      def getKeyboard = System.in
      def getConsole = System.out
      def put(p1: String, p2: AnyRef) = {}
      def get(p1: String) = null
      def format(p1: AnyRef, p2: Int) = throw new UnsupportedOperationException
      def execute(p1: CharSequence) = throw new UnsupportedOperationException
      def convert(p1: Class[_], p2: AnyRef) = throw new UnsupportedOperationException
      def close = {}
    }

    val action = new Benchmark()
    val p = new DefaultActionPreparator
    try {
      if( p.prepare(action, session, JavaConversions.asJavaList(args.toList)) ) {
        action.execute(session)
      }
    } catch {
      case x:CommandException=>
        println(x.getMessage)
        System.exit(-1);
    }
  }
}

@command(scope="amqp", name = "benchmark", description = "The AMQP benchmarking tool")
class Benchmark extends Action {
  
  // Helpers needed to differentiate between default value and not set on the CLI value for primitive values
  def toIntOption(x: java.lang.Integer): Option[Int] = if(x!=null) Some(x.intValue) else None
  def toLongOption(x: java.lang.Long): Option[Long] = if(x!=null) Some(x.longValue) else None
  def toBooleanOption(x: java.lang.Boolean): Option[Boolean] = if(x!=null) Some(x.booleanValue) else None

  @option(name = "--broker_name", description = "The name of the broker being benchmarked.")
  var cl_broker_name:String = _
  var broker_name = FlexibleProperty(default = None, high_priority = () => Option(cl_broker_name))

  @option(name = "--protocol", description = "protocol to use (tcp, ssl, tls, tlsv2, etc.)")
  var cl_protocol: String = _
  var protocol = FlexibleProperty(default = Some("tcp"), high_priority = () => Option(cl_protocol))

  @option(name = "--key-store-file", description = "The JKS keystore file to use for keys and certs when using ssl connections")
  var cl_key_store_file: String = _
  var key_store_file = FlexibleProperty(default = None, high_priority = () => Option(cl_key_store_file))

  @option(name = "--key-store-password", description = "The JKS keystore password")
  var cl_key_store_password: String = _
  var key_store_password = FlexibleProperty(default = None, high_priority = () => Option(cl_key_store_password))

  @option(name = "--key-password", description = "The password the key in the JKS keystore")
  var cl_key_password: String = _
  var key_password = FlexibleProperty(default = None, high_priority = () => Option(cl_key_password))

  @option(name = "--host", description = "server host name")
  var cl_host: String = _
  var host = FlexibleProperty(default = Some("127.0.0.1"), high_priority = () => Option(cl_host))
  @option(name = "--port", description = "server port")
  var cl_port: java.lang.Integer = _
  @option(name = "--sample-count", description = "number of samples to take")
  var cl_sample_count: java.lang.Integer = _
  var sample_count = FlexibleProperty(default = Some(15), high_priority = () => toIntOption(cl_sample_count))
  @option(name = "--sample-interval", description = "number of milli seconds that data is collected.")
  var cl_sample_interval: java.lang.Integer = _
  var sample_interval = FlexibleProperty(default = Some(1000), high_priority = () => toIntOption(cl_sample_interval))
  @option(name = "--warm-up-count", description = "number of warm up samples to ignore")
  var cl_warm_up_count: java.lang.Integer = _
  var warm_up_count = FlexibleProperty(default = Some(3), high_priority = () => toIntOption(cl_warm_up_count))

  var port = FlexibleProperty(default = Some(5672), high_priority = () => toIntOption(cl_port))

  @option(name = "--scenario-connection-scale", description = "enable the connection scale scenarios")
  var cl_scenario_connection_scale: java.lang.Boolean = _
  var scenario_connection_scale = FlexibleProperty(default = Some(false), high_priority = () => toBooleanOption(cl_scenario_connection_scale))
  @option(name = "--user", description = "user name to connect with")
  var cl_user:String = _
  var user = FlexibleProperty(default = None, high_priority = () => Option(cl_user))
  @option(name = "--password", description = "password to connect with")
  var cl_password:String = _
  var password = FlexibleProperty(default = None, high_priority = () => Option(cl_password))

  @argument(index=0, name = "scenario-file", description = "The scenario file used to define the benchmark")
  var cl_scenario_file: File = _
  var scenario_file = FlexibleProperty(default = None, high_priority = () => Option(cl_scenario_file))

  @argument(index=1, name = "out", description = "The file to store benchmark metrics in", required=true)
  var cl_out: File = _
  var out = FlexibleProperty(default = None, high_priority = () => Option(cl_out))

  @option(name = "--queue-prefix", description = "prefix used for queue destiantion names.")
  var cl_queue_prefix: String = _
  var queue_prefix = FlexibleProperty(default = Some("/queue/"), high_priority = () => Option(cl_queue_prefix))
  @option(name = "--topic-prefix", description = "prefix used for topic destiantion names.")
  var cl_topic_prefix: String = _
  var topic_prefix = FlexibleProperty(default = Some("/topic/"), high_priority = () => Option(cl_topic_prefix))

  @option(name = "--drain-timeout", description = "How long to wait for a drain to timeout in ms.")
  var cl_drain_timeout: java.lang.Long = _
  var drain_timeout = FlexibleProperty(default = Some(3000L), high_priority = () => toLongOption(cl_drain_timeout))
  @option(name = "--display-errors", description = "Should errors get dumped to the screen when they occur?")
  var cl_display_errors: java.lang.Boolean = _
  var display_errors = FlexibleProperty(default = Some(false), high_priority = () => toBooleanOption(cl_display_errors))
  
  var samples = HashMap[String, List[(Long,Long)]]()
  var benchmark_results = new BenchmarkResults()

  @option(name = "--summerize", description = "Summerize the benchmark metrics in the existing data file instead of running the benchmark.")
  var cl_summerize: Boolean = _
  var summerize = FlexibleProperty(default = Some(false), high_priority = () => toBooleanOption(cl_summerize))

  @option(name = "--trace", description = "Enable AMQP frame tracing")
  var cl_trace: Boolean = _
  var trace = FlexibleProperty(default = Some(false), high_priority = () => toBooleanOption(cl_trace))

  def json_format(value:Option[List[String]]):String = {
    value.map { json_format _ }.getOrElse("null")
  }

  def json_format(value:List[String]):String = {
    "[ "+value.mkString(",")+" ]"
  }

  def write_results() {
    val parent_dir = out.get.getParentFile
    if (parent_dir != null) {
      parent_dir.mkdirs
    }
    val os = new PrintStream(new FileOutputStream(out.get))
    os.print(benchmark_results.to_json())
    os.close
    
    println("===================================================================")
    println("Stored: "+out.get)
    println("===================================================================")
  }
  
  def execute(session: CommandSession): AnyRef = {
    
    FlexibleProperty.init_all()

    if( summerize.get ) {
      //
      // Loads and then displays the avg and stddev of the data in a benchmark file.
      //
      val mapper = new ObjectMapper()
      val data = mapper.readValue(out.get, classOf[java.util.Map[String, Object]])
      import collection.JavaConversions._

      println("%s,%s,%s".format("Scenario", "Avg", "StdDev"))
      for( (key, value) <- data ) {
        if( key== "benchmark_settings" || key.startsWith("e_") ) {
        } else {
          value match {
            case samples:java.util.List[Object] =>
              val stats = new DescriptiveStatistics();
              for( sample <- samples ) {
                val v = sample.asInstanceOf[java.util.List[Object]].get(1).asInstanceOf[Number].doubleValue()
                stats.addValue(v)
              }
              println("%s,%f,%f".format(key, stats.getMean, stats.getStandardDeviation))
            case _ =>
          }
        }
      }
      return null
    }

    broker_name.set_default(out.get.getName.stripSuffix(".json"))
    
    // Protect against ctrl-c, write the results we have in any case
    Signal.handle(new Signal("INT"), new SignalHandler () {
      def handle(sig: Signal) {
        println("\n\n**** Program interruption requested by the user, writing the results ****\n")
        write_results()
        System.exit(0)
      }
    });

    println("===================================================================")
    println("Benchmarking %s at: %s:%d".format(broker_name.get, host.get, port.get))
    println("===================================================================")

    try {
      load_and_run_benchmarks
    } catch {
      case e : Exception => {
        println("There was an error, we proceed to write the results we got:")
        println(e)
        println(e.getStackTraceString)
      }
    }
    
    write_results()

    null
  }

  private def benchmark(name:String, drain:Boolean=true, sc:Int=sample_count.get, is_done: (List[Scenario])=>Boolean = null)(init_func: (Scenario)=>Unit ):Unit = {
    multi_benchmark(List(name), drain, sc, is_done) { scenarios =>
      init_func(scenarios.head)
    }
  }

  private def multi_benchmark(names:List[String], drain:Boolean=true, sc:Int=sample_count.get, is_done: (List[Scenario])=>Boolean = null, results: HashMap[String, ClientResults] = HashMap.empty)(init_func: (List[Scenario])=>Unit ):Unit = {
    val scenarios:List[Scenario] = names.map { name=>
      val scenario = new ProtonScenario
      scenario.name = name
      scenario.sample_interval = sample_interval.get
      scenario.protocol = protocol.get
      scenario.host = host.get
      scenario.port = port.get
      scenario.key_store_file = key_store_file.getOption()
      scenario.key_store_password = key_store_password.getOption()
      scenario.key_password = key_password.getOption()
      scenario.user = user.getOption
      scenario.password = password.getOption
      scenario.queue_prefix = queue_prefix.get
      scenario.topic_prefix = topic_prefix.get
      scenario.drain_timeout = drain_timeout.get
      scenario.display_errors = display_errors.get
      scenario.trace = trace.get
      scenario
    }

    init_func(scenarios)

    scenarios.foreach{ scenario=>
      if (scenario.destination_name.isEmpty) {
       if( scenario.destination_type == "queue" ) {
         scenario.destination_name = "loadq"
       } else if( scenario.destination_type == "topic" ) {
         scenario.destination_name = "loadt"
       }
      }
    }

    print("scenario  : %s ".format(names.mkString(" and ")))

    def with_load[T](s:List[Scenario])(proc: => T):T = {
      s.headOption match {
        case Some(senario) =>
          senario.with_load {
            with_load[T](s.drop(1)) {
              proc
            }
          }
        case None =>
          proc
      }
    }

    Thread.currentThread.setPriority(Thread.MAX_PRIORITY)
    val sample_set = with_load(scenarios) {
      for( i <- 0 until warm_up_count.get ) {
        Thread.sleep(sample_interval.get)
        print(".")
      }
      scenarios.foreach(_.collection_start)

      if( is_done!=null ) {
        while( !is_done(scenarios) ) {
          print(".")
          Thread.sleep(sample_interval.get)
          scenarios.foreach(_.collection_sample)
        }

      } else {
        var remaining = sc
        while( remaining > 0 ) {
          print(".")
          Thread.sleep(sample_interval.get)
          scenarios.foreach(_.collection_sample)
          remaining-=1
        }
      }


      println(".")
      scenarios.foreach{ scenario=>
        val collected = scenario.collection_end
        collected.foreach{ x=>
          if( !x._1.startsWith("e_") || x._2.find( _._2 != 0 ).isDefined ) {
            println("%s samples: %s".format(x._1, json_format(x._2.map(_._2.toString))) )
            
            if (results.contains(scenario.name)) {
              // Copy the scenario results to the results structure
              val client_results = results(scenario.name)
              client_results.producers_data = collected.getOrElse("p_"+scenario.name, Nil)
              client_results.consumers_data = collected.getOrElse("c_"+scenario.name, Nil)
              client_results.error_data = collected.getOrElse("e_"+scenario.name, Nil)
              client_results.request_p90 = collected.getOrElse("p90_"+scenario.name, Nil)
              client_results.request_p99 = collected.getOrElse("p99_"+scenario.name, Nil)
              client_results.request_p999 = collected.getOrElse("p999_"+scenario.name, Nil)

              if ( client_results.error_data.foldLeft(0L)((a,x) => a + x._2) == 0 ) {
                // If there are no errors, we keep an empty list
                client_results.error_data = Nil
              }
            }
          }
        }
        samples ++= collected
      }
    }
    Thread.currentThread.setPriority(Thread.NORM_PRIORITY)

    if( drain) {
      scenarios.headOption.foreach( _.drain )
    }
  }

  trait propertyFunction {
    
    protected val SLEEP = -500

    protected var init_time: Long = 0

    def init(time: Long) { init_time = time }

    def now() = { System.currentTimeMillis() - init_time }

    def apply() = 0

    /* Alternates two values for short periods of time (fast) or long ones (slow) in bursts */
    def burst(slow: Int, fast: Int, duration: Int, period: Int) = {
      new Function1[Long, Int] {
        var burstLeft: Long = 0
        var previousTime: Long = 0
        def apply(time: Long) = {
          if (time != previousTime) {
            if (burstLeft > 0) {
              burstLeft -= time-previousTime
              if(burstLeft < 0){
                burstLeft = 0
              }
            } else {
              if (util.Random.nextInt(period) == 0) {
                burstLeft = duration
              }
            }
            previousTime = time
          }
          if (burstLeft > 0) fast else slow
        }
      }
    }
    
    /* Returns random numbers uniformly distributed between min (included) and max (not included) */
    def random (min: Int, max: Int) = {
      if (min == max) {
        new Function1[Long, Int] {
          def apply(time: Long): Int = {
            return min
          }
        }
      } else if (max > min) {
        new Function1[Long, Int] {
          def apply(time: Long): Int = {
            return Random.nextInt(max-min) + min
          }
        }
      } else {
        throw new Exception("Error in random function, min bigger than max.")
      }
    }
    
    /* Returns random numbers normally (gaussian) distributed with a mean and a variance */
    def normal (mean: Int, variance: Int) = {
      new Function1[Long, Int] {
        def apply(time: Long): Int = {
          return (Random.nextGaussian*variance + mean).toInt
        }
      }
    }
  }

  private def mlabel(size:Int) = if((size%1024)==0) (size/1024)+"k" else size+"b"
  private def plabel(persistent:Boolean) = if(persistent) "p" else ""
  private def slabel(sync_send:Boolean) = if(sync_send) "" else "a"

  def load_and_run_benchmarks = {
    
    var producers = FlexibleProperty[Int]() 
    var consumers = FlexibleProperty[Int]()
    var destination_type = FlexibleProperty[String]()
    var destination_name = FlexibleProperty[String]()
    var destination_count = FlexibleProperty[Int]()
    var consumer_prefix = FlexibleProperty[String]()
    
    var content_length = FlexibleProperty[Boolean]()
    
    var drain = FlexibleProperty[Boolean](default = Some(false))
    var persistent = FlexibleProperty[Boolean]()
    var producer_qos = FlexibleProperty[String]()
    var consumer_qos = FlexibleProperty[String]()
    var consumer_prefetch = FlexibleProperty[Int]()

    var max_concurrent_connects = FlexibleProperty[Int]()
    var producers_per_sample = FlexibleProperty[Int]()
    var consumers_per_sample = FlexibleProperty[Int]()

    var producer_sleep = FlexibleProperty[propertyFunction](default = Some(new propertyFunction { override def apply() = 0 }))
    var consumer_sleep = FlexibleProperty[propertyFunction](default = Some(new propertyFunction { override def apply() = 0 }))
    var message_size = FlexibleProperty[propertyFunction](default = Some(new propertyFunction { override def apply() = 1024 }))
    var messages_per_connection = FlexibleProperty[propertyFunction](default = Some(new propertyFunction { override def apply() = -1 }))

    def getStringValue(property_name: String, ns_xml: NodeSeq, vars: Map[String, String] = Map.empty[String, String]): Option[String] = {
      val value = ns_xml \ property_name
      if (value.length == 1) Some(substituteVariables(value.text.trim, vars)) else None
    }

    def getIntValue(property_name: String, ns_xml: NodeSeq, vars: Map[String, String] = Map.empty[String, String]): Option[Int] = {
      val value = getStringValue(property_name, ns_xml, vars)
      try {
        value.map((x:String) => x.toInt)
      } catch {
        case e: NumberFormatException => throw new Exception("Error in XML scenario, not integer provided: " + value.getOrElse("\"\""))
      }
    }

    def getBooleanValue(property_name: String, ns_xml: NodeSeq, vars: Map[String, String] = Map.empty[String, String]): Option[Boolean] = {
      val value = getStringValue(property_name, ns_xml, vars)
      try {
        value.map((x:String) => x.toBoolean)
      } catch {
        case e: NumberFormatException => throw new Exception("Error in XML scenario, not boolean provided: " + value.getOrElse("\"\""))
      }
    }

    def getPropertyFunction(property_name: String, clients_xml: NodeSeq, vars: Map[String, String] = Map.empty[String, String]): Option[propertyFunction] = { 
      val format_catcher = catching(classOf[NumberFormatException])
      val property_function_nodeset = clients_xml \ property_name
      val property_function_value: Option[Int] = format_catcher.opt(substituteVariables(property_function_nodeset.text.trim, vars).toInt)
      if (property_function_nodeset.length == 1 && property_function_value.isDefined) {
        Some(new propertyFunction { override def apply() = property_function_value.get })
      } else if ((property_function_nodeset \ "range").length > 0) {
        Some(new propertyFunction {
          var ranges: List[Tuple2[Int, (Long) => Int]] = Nil 
          for (range_node <- property_function_nodeset \ "range") {
            val range_value =  format_catcher.opt(substituteVariables(range_node.text.trim, vars).toInt)
            val range_end =  getStringValue("@end", range_node, vars).
              map((x:String) => x.toLowerCase().replace("end", Int.MaxValue.toString).toInt).
              map((x: Int) => if (x >= 0) x else sample_count.get*sample_interval.get + x)
            val range_burst = range_node \ "burst"
            val range_random = range_node \ "random"
            val range_normal = range_node \ "normal"
            if (range_node.text == "sleep") {
              ranges :+= Tuple2(range_end.get, (time: Long) => SLEEP)
            } else if (range_value.isDefined) {
              ranges :+= Tuple2(range_end.get, (time: Long) => range_value.get)
            } else if (range_burst.length == 1) {
              var (slow, fast, duration, period) = (100, 0 , 1, 10)
              slow = getIntValue("@slow", range_burst, vars).getOrElse(slow)
              fast = getIntValue("@fast", range_burst, vars).getOrElse(fast)
              duration = getIntValue("@duration", range_burst, vars).getOrElse(duration)
              period = getIntValue("@period", range_burst, vars).getOrElse(period)
              ranges :+= Tuple2(range_end.get, burst(slow, fast, duration, period))
            } else if (range_random.length == 1) {
              var (min, max) = (0 , 1024)
              min = getIntValue("@min", range_random, vars).getOrElse(min)
              max = getIntValue("@max", range_random, vars).getOrElse(max)
              ranges :+= Tuple2(range_end.get, random(min, max))
            } else if (range_normal.length == 1) {
              var (mean, variance) = (0 , 1)
              mean = getIntValue("@mean", range_normal, vars).getOrElse(mean)
              variance = getIntValue("@variance", range_normal, vars).getOrElse(variance)
              ranges :+= Tuple2(range_end.get, normal(mean, variance))
            } else {
              throw new Exception("Error in XML scenario, unsuported property function: "+range_node.text)
            }
          }
          ranges = ranges.sortBy(_._1)

          override def apply() = {
            val n = now
            val r = ranges.find( r => n < r._1 )
            if (r.isDefined) {
              r.get._2(n)
            } else {
              // Default values for diferent property names
              property_name match {
                case "producer_sleep" => SLEEP
                case "consumer_sleep" => SLEEP
                case "message_size" => 1024
                case "messages_per_connection" => -1
              }
            }
          }
        })
      } else {
        None
      }
    }
    
    def getPropertyHeaders(property_name: String, ns_xml: NodeSeq, vars: Map[String, String] = Map.empty[String, String]): Option[Array[Array[String]]] = {
      val headers = ns_xml \ property_name
      if (headers.length == 1) {
        Some((headers(0) \ "client_type") map { client_type =>
          (client_type \ "header") map { header =>
            substituteVariables(header.text.trim, vars)
          } toArray
        } toArray)
      } else {
        None
      }
      
      //Some() else None
    }
    
    def push_properties(node: NodeSeq, vars: Map[String, String] = Map.empty[String, String]) {
      sample_count.push(getIntValue("sample_count", node, vars))
      drain.push(getBooleanValue("drain", node, vars))
      warm_up_count.push(getIntValue("warm_up_count", node, vars))
      sample_interval.push(getIntValue("sample_interval", node, vars))
      
      user.push(getStringValue("user", node, vars))
      password.push(getStringValue("password", node, vars))
      host.push(getStringValue("host", node, vars))
      port.push(getIntValue("port", node, vars))
      producers.push(getIntValue("producers", node, vars))
      consumers.push(getIntValue("consumers", node, vars))
      destination_type.push(getStringValue("destination_type", node, vars))
      destination_name.push(getStringValue("destination_name", node, vars))
      destination_count.push(getIntValue("destination_count", node, vars))

      consumer_prefix.push(getStringValue("consumer_prefix", node, vars))
      queue_prefix.push(getStringValue("queue_prefix", node, vars))
      topic_prefix.push(getStringValue("topic_prefix", node, vars))
      content_length.push(getBooleanValue("content_length", node, vars))
      drain_timeout.push(getIntValue("drain_timeout", node, vars).map(_.toLong))
      persistent.push(getBooleanValue("persistent", node, vars))
      producer_qos.push(getStringValue("producer_qos", node, vars))
      max_concurrent_connects.push(getIntValue("max_concurrent_connects", node, vars))
      producers_per_sample.push(getIntValue("producers_per_sample", node, vars))
      consumers_per_sample.push(getIntValue("consumers_per_sample", node, vars))
      consumer_qos.push(getStringValue("consumer_qos", node, vars))
      consumer_prefetch.push(getIntValue("consumer_prefetch", node, vars))

      producer_sleep.push(getPropertyFunction("producer_sleep", node, vars))
      consumer_sleep.push(getPropertyFunction("consumer_sleep", node, vars))
      message_size.push(getPropertyFunction("message_size", node, vars))
    }
    
    def pop_properties() {
      sample_count.pop()
      drain.pop()
      warm_up_count.pop()
      sample_interval.pop()
      
      user.pop()
      password.pop()
      host.pop()
      port.pop()
      producers.pop()
      consumers.pop()
      destination_type.pop()
      destination_name.pop()
      destination_count.pop()

      consumer_prefix.pop()
      queue_prefix.pop()
      topic_prefix.pop()
      message_size.pop()
      content_length.pop()
      drain_timeout.pop()
      persistent.pop()
      producer_qos.pop()
      max_concurrent_connects.pop()
      producers_per_sample.pop()
      consumers_per_sample.pop()
      consumer_qos.pop()
      consumer_prefetch.pop()

      producer_sleep.pop()
      consumer_sleep.pop()
    }
    
    /** This fucntion generates a list of tuples, each of them containing the
      * variables to be replaced in the scenario template and the SingleScenarioResults
      * object that will keep the results for this scenario.
      * 
      * The list is generated from a list of variables and posible values, and
      * the parent of the ScenarioResults tree structure. The ScenarioResults
      * objects are linked properly. */
    def combineLoopVariables(loop_vars: List[LoopVariable], parent: LoopScenarioResults): List[(Map[String, String], SingleScenarioResults)] = loop_vars match {
      case LoopVariable(name, _, values) :: Nil => values map { v =>
        var scenario_results = new SingleScenarioResults()
        parent.scenarios :+= (v.label, scenario_results)
        (Map(name -> v.value), scenario_results)
      }
      case LoopVariable(name, _, values) :: tail => {
        values flatMap { lv =>
          var scenario_results = new LoopScenarioResults()
          parent.scenarios :+= (lv.label, scenario_results)
          val combined_tail = combineLoopVariables(tail, scenario_results)
          combined_tail map { vv => (vv._1 + (name -> lv.value), vv._2) } 
        }
      }
      case _ => Nil
    }
    
    def substituteVariables(orig: String, vars: Map[String, String]): String = {
      val format_catcher = catching(classOf[NumberFormatException])
      var modified = orig
      for ((key, value) <- vars) {
        modified = modified.replaceAll("\\$\\{"+key+"\\}", value)
        
        // Functions applied to the variable
        val int_value: Option[Int] = format_catcher.opt( value.toInt )
        val boolean_value: Option[Boolean] = format_catcher.opt( value.toBoolean )
        
        if (int_value.isDefined) {
          modified = modified.replaceAll("\\$\\{mlabel\\("+key+"\\)\\}", mlabel(int_value.get).toString)
        }
        if (boolean_value.isDefined) {
          modified = modified.replaceAll("\\$\\{slabel\\("+key+"\\)\\}", slabel(boolean_value.get).toString)
          modified = modified.replaceAll("\\$\\{plabel\\("+key+"\\)\\}", plabel(boolean_value.get).toString)
        }
      }
      modified
    }

    val scenarios_xml = XML.loadFile(scenario_file.get)
    
    val global_common_xml = scenarios_xml \ "common"
    push_properties(global_common_xml)
    
    broker_name.push(getStringValue("broker_name", scenarios_xml))
    
    benchmark_results.broker_name = broker_name.get
    benchmark_results.description = getStringValue("description", scenarios_xml).getOrElse("").replaceAll("\n", "\\\\n")
    benchmark_results.platform_name = getStringValue("platform_name", scenarios_xml).getOrElse("").replaceAll("\n", "\\\\n")
    benchmark_results.platform_desc = getStringValue("platform_desc", scenarios_xml).getOrElse("").replaceAll("\n", "\\\\n")
    
    for (group_xml <- scenarios_xml \ "group") {
      
      val group_common_xml = group_xml \ "common"
      push_properties(group_common_xml)
      
      var group_results = new GroupResults()
      benchmark_results.groups :+= group_results
      group_results.name = getStringValue("@name", group_xml).get
      group_results.description = getStringValue("description", group_xml).getOrElse("").replaceAll("\n", "\\\\n")
      
      // Parse the loop variables
      var loop_vars = (group_xml \ "loop" \ "var") map { var_xml =>
        val values = (var_xml \ "value") map { value_xml =>
          val value = value_xml.text
          var label = (value_xml \ "@label").text
          label = if (label == "") value else label // If there is no label, we use the value
          val description = (value_xml \ "@description").text
          LoopValue(value, label, description)
        } toList 
        val name = (var_xml \ "@name").text
        var label = (var_xml \ "@label").text
        label = if (label == "") name else label // If there is no label, we use the name
        LoopVariable(name, label, values)
      } toList
      
      group_results.loop = loop_vars
      

      println("Executing scenario group: "+group_results.name)
      for (scenario_xml <- group_xml \ "scenario") {
        
        // If there are no loop variables, we just have one empty map and a SingleScenarioResults
        // Otherwise, we combine the diferent values of the loop variables and generate a ScenarioResults tree
        val variables_and_result_list = if (loop_vars.isEmpty) {
          val scenario_results = new SingleScenarioResults()
          group_results.scenarios :+= scenario_results
          List((Map.empty[String, String], scenario_results)) 
        } else {
          val scenario_results = new LoopScenarioResults()
          group_results.scenarios :+= scenario_results
          combineLoopVariables(loop_vars, scenario_results)
        }
        
        for (variables_and_result <- variables_and_result_list) {
          
          val vars = variables_and_result._1
          val scenario_results = variables_and_result._2
          
          val scenario_common_xml = scenario_xml \ "common"
          push_properties(scenario_common_xml, vars)
          
          scenario_results.name = substituteVariables(getStringValue("@name", scenario_xml, vars).get, vars)
          scenario_results.label = substituteVariables(getStringValue("@label", scenario_xml, vars).getOrElse(scenario_results.name), vars)
          
          val names = (scenario_xml \ "clients").map( client => substituteVariables((client \ "@name").text, vars) ).toList
          
          var scenario_client_results = new HashMap[String, ClientResults]()
          
          println("Executing scenario: "+scenario_results.name)
          multi_benchmark(names = names, drain = drain.get, results = scenario_client_results) { scenarios =>
            for (scenario <- scenarios) {
              val clients_xml = (scenario_xml \ "clients").filter( clients => substituteVariables((clients \ "@name").text, vars) == scenario.name )
              push_properties(clients_xml, vars)
              
              var client_results = new ClientResults()
              scenario_results.clients :+= client_results
              client_results.name = getStringValue("@name", clients_xml, vars).get
              
              scenario_client_results += (scenario.name -> client_results) // To be able to fill the results from multi_benchmark
              
              // Load all the properties in the scenario
              scenario.user = user.getOption()
              scenario.password = password.getOption()
              scenario.host = host.getOrElse(scenario.host)
              scenario.port = port.getOrElse(scenario.port)
              scenario.producers = producers.getOrElse(0)
              scenario.consumers = consumers.getOrElse(0)
              scenario.destination_type = destination_type.getOrElse(scenario.destination_type)
              scenario.destination_name = destination_name.getOrElse(scenario.destination_name)
              scenario.destination_count = destination_count.getOrElse(scenario.destination_count)
    
              scenario.consumer_prefix = consumer_prefix.getOrElse(scenario.consumer_prefix)
              scenario.queue_prefix = queue_prefix.getOrElse(scenario.queue_prefix)
              scenario.topic_prefix = topic_prefix.getOrElse(scenario.topic_prefix)
              scenario.drain_timeout = drain_timeout.getOrElse(scenario.drain_timeout)
              scenario.persistent = persistent.getOrElse(scenario.persistent)
              scenario.producer_qos = producer_qos.getOrElse(scenario.producer_qos)
              scenario.max_concurrent_connects = max_concurrent_connects.getOrElse(scenario.max_concurrent_connects)
              scenario.producers_per_sample = producers_per_sample.getOrElse(scenario.producers_per_sample)
              scenario.consumers_per_sample = consumers_per_sample.getOrElse(scenario.consumers_per_sample)
              scenario.consumer_qos = consumer_qos.getOrElse(scenario.consumer_qos)
              scenario.consumer_prefetch = consumer_prefetch.getOrElse(scenario.consumer_prefetch)

              scenario.producer_sleep = producer_sleep.get
              scenario.consumer_sleep = consumer_sleep.get
              scenario.message_size = message_size.get
              scenario.messages_per_connection = messages_per_connection.get

              // Copy the scenario settings to the results
              client_results.settings = scenario.settings
              
              pop_properties()
            }
          }
          pop_properties()
        }
      }
      pop_properties()
    }
    pop_properties()
  }
}
