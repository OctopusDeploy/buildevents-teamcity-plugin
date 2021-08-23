# TeamCity Build OpenTelemetry Exporter Plugin

A TeamCity plugin that sends build trace data to an OpenTelemetry collector endpoint.

This plugin helps you visualize how you can better optimize your TeamCity builds and their dependency trees, by exporting TeamCity build pipeline data automatically to existing OpenTelemetry collector such as [Honeycomb](https://www.honeycomb.io/), [Zipkin](https://zipkin.io/) or [Jaeger](https://www.jaegertracing.io).

![trace_image.png](trace_image.png)

## What is OpenTelemetry


From the [OpenTelemetry docs](https://opentelemetry.io/docs/):

> You can use OpenTelemetry to instrument, generate, collect, and export telemetry data (metrics, logs, and traces) for analysis in order to understand your software's performance and behavior. Create and collect telemetry data from your services and software, then forward them to a variety of analysis tools. For more information about OpenTelemetry go to https://opentelemetry.io/.

## How to use this plugin

### Installing the plugin to TeamCity

1. Build the plugin using the "Building" instructions below.
2. In your TeamCity instance go to Administration -> Diagnostics -> Internal Properties.
    1. Ensure you update add a property `octopus.teamcity.opentelemetry.plugin.endpoint=<your_opentelemetry_collector_endpoint>`
    2. Ensure you update add a property `octopus.teamcity.opentelemetry.plugin.headers=<your_opentelemetry_collector_endpoint_headers>`. Separate each key value pair with `=`, and separate each header with a `,`.
3. Alternatively open the `internal.properties` file location in your TeamCity instance `data_dir/config` folder and update the above properties.
4. Install the .zip using your TeamCity instance UI via Administration -> Plugins -> Upload. Restart if required.

## Local Development

### Using Docker

For detailed instructions check the [TeamCity docker hub docs](https://hub.docker.com/r/jetbrains/teamcity-server).

1. Ensure you have docker running
2. Run `docker pull jetbrains/teamcity-server`
3. Run 
```bash 
docker run -it --name teamcity-server-instance \
   -v <path-to-data-directory>:/data/teamcity_server/datadir \
   -v <path-to-logs-directory>:/opt/teamcity/logs  \
   -p <port-on-host>:8111 \
   jetbrains/teamcity-server
```
   
5. Run `docker pull jetbrains/teamcity-agent`
6. Run 
```bash 
docker run -it -e SERVER_URL="<url to TeamCity server>"  \
   -v <path to agent config folder>:/data/teamcity_agent/conf  \      
   jetbrains/teamcity-agent
 ```

### Running Locally

You must have a TeamCity instance running. To run a TeamCity instance locally:
1. Download the TeamCity distro for your OS from https://www.jetbrains.com/teamcity/download/#section=on-premises.
2. Unzip the distro and place in a directory of your choosing. 
3. To run the default server, open terminal then cd in your TeamCity directory then run `./bin/teamcity-server.sh run`. An alias these commands for repeated future can be made.
4. In a separate terminal, to run the default agent, cd in your TeamCity directory then run `./buildAgent/bin/agent.sh run`.
5. Open localhost:8111 in a browser.
6. On your first run create an admin login (this setup only needs to take place once due to the configuration mount). Once the server starts up, navigate to Agents->Unauthorized and authorise the agent that was started in a container alongside the server.
7. (Optional) If attaching a remote debugger run in your TeamCity directory `export TEAMCITY_SERVER_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8111 && $SCRIPT_PATH/bin/teamcity-server.sh run` for the server and `export TEAMCITY_SERVER_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8111 && $SCRIPT_PATH/buildAgent/bin/agent.sh run` for the default agent.
8. In your TeamCity instance go to Administration -> Diagnostics -> Internal Properties.
   1. Ensure you update add a property `octopus.teamcity.opentelemetry.plugin.endpoint=<your_opentelemetry_collector_endpoint>`
   2. Ensure you update add a property `octopus.teamcity.opentelemetry.plugin.headers=<your_opentelemetry_collector_endpoint_required_headers>`. Separate each key value pair with `=`, and separate each header with a `,`.
9. To stop the TeamCity server and agent from running, in a separate terminal cd to your TeamCity directory and run `./bin/runAll.sh stop`

### Building

To build the plugin from code:
1. Ensure your `$JAVA_HOME` points to a java11 JDK installation
2. Install TeamCity
3. Inside the root project folder run `./gradlew build`. The gradlew script will download Gradle for you if it is not already installed.
4. The plugin is available at `<project_root>/build/distributions/Octopus.TeamCity.OpenTelemetry.<version>.zip`.
5. Copy to `.zip` to your TeamCity `data_dir/plugins` directory and restart TeamCity server OR install the `.zip` using your TeamCity instance UI.

### Testing

JUnit tests have been added to package test folders.

To test the plugin from code:
1. Ensure your `$JAVA_HOME` points to a java11 JDK installation
2. Inside the root project folder run `./gradlew test`. The gradlew script will download Gradle for you if it is not already installed.

### Cleaning

To clean the project root directory of builds:
1. Inside the root project folder run `./gradlew clean`. The gradlew script will download Gradle for you if it is not already installed.

