# AMQP Benchmark

A benchmarking tool for [AMQP 1.0](http://www.amqp.org/resources/specifications) servers.
The benchmark covers a wide variety of common usage scenarios.

<!--
# Just looking for the Results?

The numbers look different depending on the Hardware and OS they are run on:

* [Amazon Linux: EC2 High-CPU Extra Large Instance](http://hiramchirino.com/stomp-benchmark/ec2-c1.xlarge/index.html)
* [Ubuntu 11.10: Quad-Core 2600k Intel CPU (3.4 GHz)](http://hiramchirino.com/stomp-benchmark/ubuntu-2600k/index.html)
* [OS X: 2 x Quad-Core Intel Xeon (3 GHz)](http://hiramchirino.com/stomp-benchmark/osx-8-core/index.html)
-->

## Servers Currently Benchmarked

* Apache ActiveMQ
* Apache ActiveMQ Apollo

<!--
* RabbitMQ
* HornetQ
-->

## Running the Benchmark

Running the benchmark against a running AMQP server:

  ./bin/benchmark scenarios.xml result.json

That will execute the benchmarking scenarios defined in `scenarios.xml` and
store the results in `result.json`.

Or if you want to auto download and setup, startup, and benchmark
several AMQP servers, just run:

    ./bin/benchmark-all
    
Or if you just want to do it to specific AMQP server implementation:

    ./bin/benchmark-activemq-snapshot

Tested to work on:

* Ubuntu 11.10
* Amazon Linux
* OS X

You can visualize the benchmark results by browsing the `reports/index.html` file.

In google chrome, if you use file:///, the same origin policy wont allow to load 
the results.  You can relax that restriction in Chrome by starting it with 
the `--allow-file-access-from-files` argument.  On OS X you can do that with 
the following command: 

    open -a 'Google Chrome' --args --allow-file-access-from-files

## Running the Benchmark on an EC2 Amazon Linux 64 bit AMI

If you want to run the benchmark on EC2, we recommend using at least the
c1.xlarge instance type.  Once you have the instance started just execute
the following commands on the instance:

    sudo yum install -y screen
    curl https://nodeload.github.com/chirino/amqp-benchmark/tarball/master | tar -zxv 
    mv chirino-amqp-benchmark-* amqp-benchmark
    screen ./amqp-benchmark/bin/benchmark-all

The results will be stored in the ~/reports directory.

## Customizing the Benchmarking Scenarios

The scenario file has a "scenarios" root element. Inside, first you define
some information that will be displayed on the report. Afterwards, you can
place a common section, defining values for some properties to be used in all
the scenarios (exceptions can be made, redefining the value in a lower
level).

After the common section, you can define one or more groups, and give them a name. Also, it can have a description, and a common section as before.

For simple groups, you can just start defining scenarios.

Then you can define one or more scenarios, and give them a name (internal
name) and a label (it will be displayed in the report). You can also define a
common section here.

Then, you just have to create one or more clients sections, and define the
properties for this clients. All clients in one scenario will run in
parallel, but scenarios will run in sequence.

For more complex groups, you can define variables inside a loop section, and give different values to each variable. All the possible combinations of the values for each variable will be generated,
and a scenario for each combination will be generated using a scenario template. A scenario template is defined as a normal scenario, but you can use placeholders like ${variable_name} that will be substituted with the real value.

The use of multiple scenario templates in one group with loop variables is
supported in stomp-benchmark, but only the first one will be displayed using
the generic_report.html. Also note, that if more than 1 variable is defined,
a table will be used to display the results. The odd variables (in definition
order) will be horizontal headers, the even ones, vertical headers.

The last thing to note, is that for the properties producer_sleep and
consumer_sleep, message_size and messages_per_connection, instead of
providing a single value, different values or functions for different time
ranges can be provided.

In a range, you can specify the value to be used up to the millisecond
specified in the `end` attribute. The `end` attribute can take positive
values, negative values counting from the end, or the word "end". This way,
it's possible to write scenarios that are independent from the scenario
duration.

For the values, it's possible to provide three different functions: burst
(with fast value, slow value, duration of the fast period, and period of
bursts), random (with min and max values) and normal(with mean and variance
values).

For example you could define:

    <producer_sleep>
        <range end="10000">sleep</range>
        <range end="15000">0</range>
        <range end="-10000"><burst fast="0" slow="100" duration="500" period="10000" /></range>
        <range end="end">sleep</range>
    </producer_sleep>

That means, form 0ms until 10000ms, don't send any message. From 10000ms to
15000ms, send as fast as possible. From 15000ms to 70000ms, send in bursts,
sometimes fast, sometimes slow. The fast value is the sleep time when it's in
a burst, the slow value is the sleep time when it isn't in a burst, the
duration of the burst, and period is the period of time when, in average, a
burst should occur. So, in this case, in average, every 10 seconds we will
have a burst of 0.5 seconds, sending as fast as possible. The rest of the
time, is sending slow.

There are some properties that can only be defined in the common sections:
sample_interval, sample_count, drain, blocking_io and warm_up_count.

These properties can be defined anywhere: login, passcode, host, port,
producers, consumers, destination_type, destination_name, consumer_prefix,
queue_prefix, topic_prefix, message_size, content_length, drain_timeout,
persistent, durable, sync_send, ack, messages_per_connection,
producers_per_sample, consumers_per_sample, selector, producer_sleep,
consumer_sleep

Example:

    <scenarios>
        <broker_name>Test Broker</broker_name>
        <description>This is the general description for the scenarios in this file.</description>
        <platform_name>Test Platform</platform_name>
        <platform_desc>Platform description</platform_desc>
        <common>
            <sample_interval>1000</sample_interval>
            <blocking_io>false</blocking_io>
            <warm_up_count>3</warm_up_count>
            <drain>true</drain>
        </common>
        <group name="Persistent Queue Load/Unload - Non Persistent Queue Load">
            <description>
                Persistent Queue Load/Unload - Non Persistent Queue Load
            </description>
            <common>
                <sample_count>30</sample_count>
                <destination_type>queue</destination_type>
                <destination_count>1</destination_count>
                <destination_name>load_me_up</destination_name>
            </common>
            <scenario name="non_persistent_queue_load" label="Non Persistent Queue Load">
                <clients name="20b_1a_1queue_0">
                    <producers>1</producers>
                    <consumers>0</consumers>
                    <message_size>20</message_size>
                    <persistent>false</persistent>
                </clients>
            </scenario>
            <scenario name="persistent_queue_load" label="Persistent Queue Load">
                <common>
                    <drain>false</drain>
                </common>
                <clients name="20b_1p_1queue_0">
                    <producers>1</producers>
                    <consumers>0</consumers>
                    <message_size>20</message_size>
                    <persistent>true</persistent>
                </clients>
            </scenario>
            <scenario name="persistent_queue_unload" label="Persistent Queue Unload">
                <clients name="20b_0_1queue_1">
                    <producers>0</producers>
                    <consumers>1</consumers>
                </clients>
            </scenario>
        </group>
        <group name="Fast and Slow Consumers">
            <loop>
                <var name="destination_type" label="Destination type">
                    <value label="Queue">queue</value>
                    <value label="Topic">topic</value>
                </var>
            </loop>
            <description>
                Scenario with fast and slow consumers
            </description>
            <scenario name="fast_slow_consumers_${destination_type}" label="Fast and Slow consumers on a ${destination_type}">
                <common>
                    <sample_count>15</sample_count>
                    <destination_type>${destination_type}</destination_type>
                    <destination_count>1</destination_count>
                </common>
                <clients name="20b_1a_1${destination_type}_1fast">
                    <producers>1</producers>
                    <consumers>1</consumers>
                    <message_size>20</message_size>
                    <persistent>false</persistent>
                </clients>
                <clients name="20b_1a_1${destination_type}_1slow">
                    <producers>0</producers>
                    <consumers>1</consumers>
                    <consumer_sleep>100</consumer_sleep>
                </clients>
            </scenario>
        </group>
    <scenarios>


You might also want to look at the [custom-scenario.md ](https://github.com/chirino/amqp-benchmark/blob/master/custom-scenario.md) 
file for more information on how to run a benchmark scenario from the Scala REPL.

## Command Line Usage

    $ ./bin/benchmark --help
    DESCRIPTION
            >:benchmark

    	The AMQP benchmarking tool

    SYNTAX
            >:benchmark [options] scenario-file out 

    ARGUMENTS
            scenario-file
                    The scenario file used to define the benchmark
            out
                    The file to store benchmark metrics in

    OPTIONS
            --broker_name
                    The name of the broker being benchmarked.
            --protocol
                    protocol to use (tcp, ssl, tls, tlsv2, etc.)
            --display-errors
                    Should errors get dumped to the screen when they occur?
            --summerize
                    Summerize the benchmark metrics in the existing data file 
                    instead of running the benchmark.
            --trace
                    Enable AMQP frame tracing
            --sample-count
                    number of samples to take
            --port
                    server port
            --key-store-password
                    The JKS keystore password
            --drain-timeout
                    How long to wait for a drain to timeout in ms.
            --host
                    server host name
            --queue-prefix
                    prefix used for queue destiantion names.
            --user
                    user name to connect with
            --warm-up-count
                    number of warm up samples to ignore
            --key-password
                    The password the key in the JKS keystore
            --sample-interval
                    number of milli seconds that data is collected.
            --help
                    Display this help message
            --topic-prefix
                    prefix used for topic destiantion names.
            --password
                    password to connect with
            --key-store-file
                    The JKS keystore file to use for keys and certs when using ssl 
                    connections
            --scenario-connection-scale
                    enable the connection scale scenarios

