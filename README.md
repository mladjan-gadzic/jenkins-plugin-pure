# Jenkins Armada Plugin

Jenkins plugin to run dynamic agents in a Kubernetes cluster using Armada.

## Prerequisites

Before running this plugin, you need the following:

### Required Software

- Java 21
- Maven 3.9.9 (or use mvn wrapper plugin by running `mvn wrapper:wrapper` and continue with `./mvnw` as mvn executable)
- Running Armada operator in your Kubernetes cluster
- Kubernetes cluster with Armada configured

### Armada Setup

1. Install and run the Armada operator in your Kubernetes cluster
2. Create an example queue in Armada
3. For detailed instructions on setting up Armada, refer to
   the [Armada operator's README](https://github.com/armadaproject/armada-operator)

### Configuration File

You need to have a `clusters.xml` file that defines your Kubernetes cluster connection. An example
is provided in this repository:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<clusters>
  <cluster>
    <name>Cluster1</name>
    <url>https://127.0.0.1:62179</url>
  </cluster>
</clusters>
```

## Running the Plugin

Follow these steps to run the plugin in development mode:

### 1. Prepare the Environment

Copy the clusters configuration file to `/tmp`:

```bash
cp clusters.xml /tmp/clusters.xml
```

### 2. Clean Previous State (Optional)

To ensure a clean state, remove the work directory:

```bash
rm -rf work
```

### 3. Start Jenkins with the Plugin

Run Jenkins in development mode using Maven:

```bash
mvn hpi:run
```

This will start Jenkins on `http://localhost:8080/jenkins` (default port).

### 4. Configure the Armada Cloud

1. Open Jenkins in your browser: `http://localhost:8080/jenkins`
2. Navigate to **Manage Jenkins** → **Manage Nodes and Clouds** → **Configure Clouds**
3. Click **Add a new cloud** and select **Armada**
4. Configure the cloud:
    - **Name**: Choose any name (e.g., "armada-cloud")
    - Leave other fields at their default values for now
5. Click **Save**

### 5. Create a Pipeline Job

1. From the Jenkins dashboard, click **New Item**
2. Enter a name for your job
3. Select **Pipeline** as the job type
4. Click **OK**
5. In the Pipeline configuration section:
    - Under **Definition**, keep it as "Pipeline script"
    - In the **Script** field, paste the contents of the `Jenkinsfile` from this repository:

```groovy
pipeline {
  agent {
    armada {
      yaml '''
        apiVersion: v1
        kind: Pod
        spec:
          containers:
          - name: maven
            image: maven:3.9.9-eclipse-temurin-17
            command:
            - cat
            tty: true
            resources:
              requests:
                memory: "256Mi"
                cpu: "500m"
              limits:
                memory: "256Mi"
                cpu: "500m"
          - name: busybox
            image: busybox
            command:
            - cat
            tty: true
            resources:
              requests:
                memory: "256Mi"
                cpu: "500m"
              limits:
                memory: "256Mi"
                cpu: "500m"
        '''
    }
  }
  stages {
    stage('Run maven') {
      steps {
        armadaContainer('maven') {
          sh 'mvn -version'
        }
        armadaContainer('busybox') {
          sh '/bin/busybox'
        }
      }
    }
  }
}
```

6. Click **Save**

### 6. Run the Pipeline

1. From your job page, click **Build Now**
2. Watch the build progress in the **Build History** section
3. Click on the build number to view details and console output

## Debugging

To run Jenkins with remote debugging enabled:

```bash
export MAVEN_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,address=5005,suspend=n"
mvn hpi:run
```

Then create a Remote Debug configuration in IntelliJ IDEA (JDK 9 or later) with port 5005.

## Troubleshooting

- If Jenkins doesn't start, ensure Java 21 and Maven 3.9.9 are properly installed
- If the Armada cloud doesn't appear, verify the clusters.xml is in `/tmp`
- If builds fail, check that the Armada operator is running and the queue is configured correctly
- All Jenkins data and logs are stored in the `work` directory

## License

Apache License 2.0
