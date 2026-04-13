### How Apps Become Agentic

#### Manifest and AIDL Service Examples

The following comprehensive examples demonstrate the use of supported manifest attributes and detailed AIDL interface implementations for the invokeTool, readResource, and listResources methods.

#### Example Manifest
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.agenticapp">

    <application
        android:label="AgenticApp"
        android:icon="@mipmap/ic_launcher">
        <service
            android:name="com.example.agenticapp.AgentService"
            android:exported="true">
            <intent-filter>
                <action android:name="com.example.agenticapp.ACTION_BIND" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

#### AIDL Interface
```aidl
package com.example.agenticapp;

interface IAgentService {
    void invokeTool(String toolName);
    String readResource(String resourceName);
    List<String> listResources();
}
```

#### Implementation of AIDL Interface
```java
public class AgentService extends Service {
    
    @Override
    public void onCreate() {
        super.onCreate();
        // Initialization logic
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private final IAgentService.Stub binder = new IAgentService.Stub() {
        @Override
        public void invokeTool(String toolName) {
            // Implementation to invoke a tool
        }

        @Override
        public String readResource(String resourceName) {
            // Implementation to read a resource
            return "Resource Data";
        }

        @Override
        public List<String> listResources() {
            // Implementation to list resources
            return new ArrayList<>(Arrays.asList("Resource1", "Resource2"));
        }
    };
}
```

### Cross-Referencing Manifest Declarations
- The `AgentService` defined in the manifest must be implemented with the AIDL interface to ensure that clients can successfully interact with it. Make sure that all declared actions and service bindings are correctly implemented in your service class.