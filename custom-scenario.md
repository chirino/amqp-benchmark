# Running a Benchmark Scenario from the Scala REPL

If there is a particular scenario you want to manually execute against the 
server, you can do so by starting up the Scala interactive interpreter by
running `mvn compile scala:console`

Then at the console you execute:

    scala> val scenario = new org.fusesource.amqp.benchmark.ProtonScenario
    scenario: org.fusesource.amqp.benchmark.ProtonScenario = 
    --------------------------------------
    Scenario Settings
    --------------------------------------
      host                  = 127.0.0.1
      port                  = 5672
      destination_type      = queue
      queue_prefix          = /queue/
      topic_prefix          = /topic/
      destination_count     = 1
      destination_name      = 
      sample_interval (ms)  = 1000
  
      --- Producer Properties ---
      producers             = 1
      message_size          = 1024
      persistent            = false
      producer_qos          = AT_LEAST_ONCE
      producer_sleep (ms)   = 0
  
      --- Consumer Properties ---
      consumers             = 1
      consumer_sleep (ms)   = 0
      consumer_prefix       = consumer-

This creates a new ProtonScenario object which you can adjust it's properties
and then run by executing `scenario.run`. For example, to run 10 producers
and no consumers, you would update the scenario object properties as follows:

    scala> scenario.producers = 10
    scala> scenario.consumers = 0

When you actually run the scenario, you it will report back the throughput
metrics. Press enter to stop the run.

    scala> scenario.run                                          
    --------------------------------------
    Scenario Settings
    --------------------------------------
      host                  = 127.0.0.1
      port                  = 61613
      destination_type      = topic
      destination_count     = 1
      destination_name      = load
      sample_interval (ms)  = 1000
  
      --- Producer Properties ---
      producers             = 10
      message_size          = 1024
      persistent            = false
      sync_send             = false
      content_length        = true
      producer_sleep (ms)   = 0
      headers               = List()
  
      --- Consumer Properties ---
      consumers             = 0
      consumer_sleep (ms)   = 0
      ack                   = auto
      selector              = null
      durable               = false
    --------------------------------------
         Running: Press ENTER to stop
    --------------------------------------

    Producer total: 345,362, rate: 345,333.688 per second
    Producer total: 725,058, rate: 377,908.125 per second
    Producer total: 1,104,673, rate: 379,252.813 per second
    Producer total: 1,479,280, rate: 373,913.750 per second
    ... <enter pressed> ...
    
    scala>



