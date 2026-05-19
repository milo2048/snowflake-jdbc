package net.snowflake.client.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.node.ObjectNode;
import net.snowflake.client.api.driver.SnowflakeDriver;
import net.snowflake.client.internal.jdbc.telemetry.TelemetryClient;
import net.snowflake.client.internal.jdbc.telemetry.TelemetryField;
import net.snowflake.common.core.LoginInfoDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ConnectionIdentifierShapeTelemetry}. Mirrors the Go reference ({@code
 * connection_identifier_shape_telemetry_test.go}) and the Python reference ({@code
 * test_connection_identifier_shape_telemetry.py}): emits exactly one record with the expected
 * payload, honors the env-var kill switch case-insensitively, and treats every non-"true" value
 * (including former truthy aliases like {@code "1"} / {@code "yes"}) as enabled.
 */
public class ConnectionIdentifierShapeTelemetryTest {

  private static ConnectionIdentifierShape sampleShape() {
    return ConnectionIdentifierShape.captureFromTomlConfig(
        new java.util.HashMap<String, String>() {
          {
            put("account", "myorg-myacct.us-east-1");
          }
        });
  }

  @Test
  public void emit_writesExpectedPayload() {
    TelemetryClient client = mock(TelemetryClient.class);

    ConnectionIdentifierShapeTelemetry.emit(client, sampleShape(), /* disableEnvValue= */ null);

    ArgumentCaptor<ObjectNode> captor = forClass(ObjectNode.class);
    verify(client, times(1)).addLogToBatch(captor.capture(), anyLong());

    ObjectNode msg = captor.getValue();
    assertEquals(
        TelemetryField.CONNECTION_IDENTIFIER_SHAPE.toString(),
        msg.get(TelemetryField.TYPE.toString()).asText());
    assertEquals(LoginInfoDTO.SF_JDBC_APP_ID, msg.get(TelemetryField.SOURCE.toString()).asText());
    assertEquals(
        LoginInfoDTO.SF_JDBC_APP_ID, msg.get(TelemetryField.DRIVER_TYPE.toString()).asText());
    assertEquals(
        SnowflakeDriver.getImplementationVersion(),
        msg.get(TelemetryField.DRIVER_VERSION.toString()).asText());
    assertEquals("true", msg.get(ConnectionIdentifierShape.ACCOUNT_PROVIDED_KEY).asText());
    assertEquals("true", msg.get(ConnectionIdentifierShape.ACCOUNT_WITH_REGION_KEY).asText());
    assertEquals("true", msg.get(ConnectionIdentifierShape.ACCOUNT_ORG_PROVIDED_KEY).asText());
    // Region structurally false on JDBC; still emitted for cross-driver join consistency.
    assertEquals("false", msg.get(ConnectionIdentifierShape.REGION_PROVIDED_KEY).asText());
    // TOML-path shape: account-only, no host key — host_provided=false.
    assertEquals("false", msg.get(ConnectionIdentifierShape.HOST_PROVIDED_KEY).asText());
  }

  @Test
  public void emit_skipsWhenShapeIsNull() {
    TelemetryClient client = mock(TelemetryClient.class);

    ConnectionIdentifierShapeTelemetry.emit(client, /* shape= */ null, null);

    verify(client, never()).addLogToBatch(any(ObjectNode.class), anyLong());
  }

  @Test
  public void emit_skipsWhenClientIsNull() {
    // Smoke test: passing a null client must not NPE.
    ConnectionIdentifierShapeTelemetry.emit(null, sampleShape(), null);
  }

  @Test
  public void emit_skipsWhenEnvVarIsLiteralTrue() {
    TelemetryClient client = mock(TelemetryClient.class);

    ConnectionIdentifierShapeTelemetry.emit(client, sampleShape(), "true");

    verify(client, never()).addLogToBatch(any(ObjectNode.class), anyLong());
  }

  @Test
  public void emit_envVarKillSwitchIsCaseInsensitive() {
    // Strict equality after lower-case folding — no whitespace tolerance — mirroring
    // gosnowflake's strings.EqualFold(..., "true") and the byte-identical Python / Node.js
    // checks. Any drift here breaks the cross-driver promise that an operator setting the
    // kill switch sees the same behavior on every driver.
    for (String value : new String[] {"True", "TRUE", "TrUe"}) {
      TelemetryClient client = mock(TelemetryClient.class);
      ConnectionIdentifierShapeTelemetry.emit(client, sampleShape(), value);
      verify(client, never()).addLogToBatch(any(ObjectNode.class), anyLong());
    }
  }

  @Test
  public void emit_envVarNonTrueValuesLeaveEmissionEnabled() {
    // Match the Go/Python/Node.js reference: only literal case-insensitive "true" disables.
    // Former truthy aliases like "1" / "yes" / "false" / empty all leave emission enabled,
    // and — critically — so do whitespace-wrapped values like " true ", "\ttrue\n", "true ".
    // The siblings explicitly call out the no-trim semantics; JDBC must agree.
    for (String value :
        new String[] {
          "",
          "0",
          "1",
          "yes",
          "Yes",
          "false",
          "no",
          "anything-else",
          " true ",
          "\ttrue\n",
          "  true  ",
          "true ",
          " true"
        }) {
      TelemetryClient client = mock(TelemetryClient.class);
      ConnectionIdentifierShapeTelemetry.emit(client, sampleShape(), value);
      verify(client, times(1)).addLogToBatch(any(ObjectNode.class), anyLong());
    }
  }

  @Test
  public void disabledByEnv_only_recognizes_case_insensitive_true() {
    assertTrue(ConnectionIdentifierShapeTelemetry.disabledByEnv("true"));
    assertTrue(ConnectionIdentifierShapeTelemetry.disabledByEnv("True"));
    assertTrue(ConnectionIdentifierShapeTelemetry.disabledByEnv("TRUE"));
    // No whitespace tolerance — see comment on disabledByEnv: the cross-driver contract is
    // "an operator accidentally exporting ` true ` (with stray whitespace) leaves emission
    // enabled". Locking this in here so a future regression to .trim() flips a red test.
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv("  true  "));
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv(" true "));
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv("\ttrue\n"));
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv("true "));
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv(" true"));
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv(null));
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv(""));
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv("1"));
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv("yes"));
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv("false"));
    assertFalse(ConnectionIdentifierShapeTelemetry.disabledByEnv("trueish"));
  }

  @Test
  public void toTelemetryNode_carriesAllFiveWireKeys() {
    // Defensive: even an all-false shape must emit every key with the expected value.
    ObjectNode node =
        ConnectionIdentifierShapeTelemetry.toTelemetryNode(ConnectionIdentifierShape.empty());

    assertNotNull(node.get(ConnectionIdentifierShape.ACCOUNT_PROVIDED_KEY));
    assertNotNull(node.get(ConnectionIdentifierShape.ACCOUNT_WITH_REGION_KEY));
    assertNotNull(node.get(ConnectionIdentifierShape.ACCOUNT_ORG_PROVIDED_KEY));
    assertNotNull(node.get(ConnectionIdentifierShape.REGION_PROVIDED_KEY));
    assertNotNull(node.get(ConnectionIdentifierShape.HOST_PROVIDED_KEY));
    assertEquals("false", node.get(ConnectionIdentifierShape.ACCOUNT_PROVIDED_KEY).asText());
    assertEquals("false", node.get(ConnectionIdentifierShape.HOST_PROVIDED_KEY).asText());
  }
}
