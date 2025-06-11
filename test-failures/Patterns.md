# Classification of Build and Test Log Patterns

This table distinguishes between **true test failures** (assertions) and other types of **runtime or configuration errors** that are **not assertion failures**.

---

## ✅ Test Failures (Assertion Failures)

> These indicate that the code executed, but the output did not match the expected result (e.g., an `assert` failed).

| Log Pattern / Exception Class                     | Description                                                |
|---------------------------------------------------|------------------------------------------------------------|
| `java.lang.AssertionError`                        | Indicates an assertion failure in the test                 |
| `org.junit.ComparisonFailure`                     | JUnit failure due to expected vs actual mismatch           |
| `org.opentest4j.AssertionFailedError`             | Assertion failed (used in JUnit 5 and Jupiter engine)      |
| `expected:<X> but was:<Y>`                        | Output did not match expected value                        |

---

## ❌ Runtime or Configuration Errors (Not Assertion Failures)

> These are execution-time or environment-related failures, not caused by failing assertions.

| Log Pattern / Exception Class                                              | Description                                                  |
|---------------------------------------------------------------------------|--------------------------------------------------------------|
| `java.lang.IllegalStateException`                                         | Illegal state during execution                               |
| `org.codehaus.plexus.archiver.ArchiverException`                          | Archiving failure during build                               |
| `org.mockito.exceptions.misusing.UnfinishedMockingSessionException`       | Mocking session not completed properly                       |
| `java.lang.NoSuchMethodError`                                             | Method not found due to binary incompatibility               |
| `javax.ws.rs.ProcessingException`                                         | Error in HTTP or REST processing                             |
| `org.json.JSONException`                                                  | Error parsing JSON                                           |
| `com.google.api.gax.rpc.ApiException`                                     | Failure in Google API call                                   |
| `io.dropwizard.configuration.ConfigurationParsingException`               | Malformed configuration file                                 |
| `java.lang.AbstractMethodError`                                           | Abstract method not implemented at runtime                   |
| `org.springframework.beans.BeanInstantiationException`                    | Failure creating Spring bean                                 |
| `java.io.IOException`                                                     | Input/output error                                           |
| `java.util.concurrent.ExecutionException`                                 | Error during async task execution                            |
| `java.lang.RuntimeException`                                              | Generic execution exception                                  |
| `java.lang.VerifyError`                                                  | Bytecode verification error                                  |
| `javax.servlet.ServletException`                                          | Servlet lifecycle failure                                    |
| `java.lang.NoClassDefFoundError`                                          | Class not found at runtime                                   |
| `java.io.FileNotFoundException`                                           | File not found                                               |
| `java.lang.IllegalArgumentException`                                      | Invalid method argument                                      |
| `org.aspectj.lang.NoAspectBoundException`                                 | AspectJ configuration error                                  |
| `uk.gov.pay.connector.gateway.util.XMLUnmarshallerException`              | XML deserialization failure                                  |
| `java.lang.Exception: java.lang.ExceptionInInitializerError`              | Static initializer failed                                    |
| `java.lang.UnsupportedClassVersionError`                                  | Class compiled with a newer Java version                     |
| `chat.tamtam.botapi.exceptions.APIException`                              | API exception from external service                          |
| `java.lang.ClassCastException`                                            | Invalid type casting                                         |
| `java.lang.NullPointerException`                                          | Null object dereferenced                                     |
| `java.lang.NoSuchFieldError`                                              | Field not found due to binary mismatch                       |
