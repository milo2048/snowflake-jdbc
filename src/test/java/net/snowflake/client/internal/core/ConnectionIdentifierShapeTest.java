package net.snowflake.client.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConnectionIdentifierShape#captureFromUrlAndProperties} and {@link
 * ConnectionIdentifierShape#captureFromTomlConfig}. Mirrors the truth-table coverage in the Go
 * reference (gosnowflake#1797 — {@code internal/config/connection_identifier_shape_test.go}) and
 * the Python reference (snowflake-connector-python#2877 — {@code
 * test/unit/test_connection_identifier_shape.py}) adjusted for the JDBC-specific divergences:
 *
 * <ul>
 *   <li>JDBC has no region knob, so {@code regionProvided} is structurally always {@code false}.
 *   <li>The JDBC URL form requires a non-empty host, so {@code hostProvided} via {@link
 *       ConnectionIdentifierShape#captureFromUrlAndProperties} is structurally always {@code true}.
 * </ul>
 *
 * <p>Telemetry emission is covered separately in {@link ConnectionIdentifierShapeTelemetryTest}.
 */
public class ConnectionIdentifierShapeTest {

  private static final String URL = "jdbc:snowflake://myacct.snowflakecomputing.com:443";

  @Test
  public void urlPath_bareUrl_noPropertiesAccount() {
    // Account inferred from host later — not user-supplied.
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(URL, new Properties());

    assertFalse(shape.isAccountProvided());
    assertFalse(shape.isAccountWithRegion());
    assertFalse(shape.isAccountOrgProvided());
    assertFalse(shape.isRegionProvided());
    assertTrue(shape.isHostProvided());
  }

  @Test
  public void urlPath_accountInUrlQuery_bareLocator() {
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(
            URL + "?account=myacct", new Properties());

    assertTrue(shape.isAccountProvided());
    assertFalse(shape.isAccountWithRegion());
    assertFalse(shape.isAccountOrgProvided());
    assertFalse(shape.isRegionProvided());
    assertTrue(shape.isHostProvided());
  }

  @Test
  public void urlPath_accountInUrlQuery_dottedRegionTail() {
    // Dot in raw account string flips accountWithRegion; the "us-east-1" tail's dashes must
    // NOT flip accountOrgProvided.
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(
            URL + "?account=myacct.us-east-1", new Properties());

    assertTrue(shape.isAccountProvided());
    assertTrue(shape.isAccountWithRegion());
    assertFalse(shape.isAccountOrgProvided(), "region-tail dashes must not trigger org-account");
    assertFalse(shape.isRegionProvided());
    assertTrue(shape.isHostProvided());
  }

  @Test
  public void urlPath_orgPrefixedAccount() {
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(
            URL + "?account=myorg-myacct", new Properties());

    assertTrue(shape.isAccountProvided());
    assertFalse(shape.isAccountWithRegion());
    assertTrue(shape.isAccountOrgProvided());
    assertFalse(shape.isRegionProvided());
    assertTrue(shape.isHostProvided());
  }

  @Test
  public void urlPath_orgPrefixedAccount_withRegionTail() {
    // Both signals at once: dot AND dash in account portion before the dot.
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(
            URL + "?account=myorg-myacct.us-east-1", new Properties());

    assertTrue(shape.isAccountProvided());
    assertTrue(shape.isAccountWithRegion());
    assertTrue(shape.isAccountOrgProvided());
    assertFalse(shape.isRegionProvided());
    assertTrue(shape.isHostProvided());
  }

  @Test
  public void urlPath_accountInProperties_overridesUrlQuery() {
    // Match SnowflakeConnectString.parse semantics: Properties account wins over URL-query account.
    Properties info = new Properties();
    info.setProperty("account", "myorg-other");

    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(URL + "?account=myacct", info);

    assertTrue(shape.isAccountProvided());
    assertTrue(shape.isAccountOrgProvided());
    assertFalse(shape.isAccountWithRegion());
  }

  @Test
  public void urlPath_emptyAccountKwarg_isNotProvided() {
    Properties info = new Properties();
    info.setProperty("account", "");

    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(URL, info);

    assertFalse(shape.isAccountProvided());
    assertTrue(shape.isHostProvided());
  }

  @Test
  public void urlPath_nullUrl_doesNotThrow() {
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(null, new Properties());

    assertFalse(shape.isAccountProvided());
    // hostProvided is still reported as true: see ConnectionIdentifierShape javadoc — capture is
    // invoked from a code path that has already established the URL form requires a host.
    assertTrue(shape.isHostProvided());
  }

  @Test
  public void urlPath_nullProperties_doesNotThrow() {
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(URL + "?account=myacct", null);

    assertTrue(shape.isAccountProvided());
    assertTrue(shape.isHostProvided());
  }

  @Test
  public void tomlPath_accountOnly() {
    Map<String, String> config = new HashMap<>();
    config.put("account", "myacct");
    config.put("user", "u");

    ConnectionIdentifierShape shape = ConnectionIdentifierShape.captureFromTomlConfig(config);

    assertTrue(shape.isAccountProvided());
    assertFalse(shape.isAccountWithRegion());
    assertFalse(shape.isAccountOrgProvided());
    assertFalse(shape.isRegionProvided(), "JDBC has no region knob in connections.toml");
    assertFalse(shape.isHostProvided(), "host synthesized later — must not flip hostProvided");
  }

  @Test
  public void tomlPath_accountWithDottedRegion() {
    Map<String, String> config = new HashMap<>();
    config.put("account", "myacct.us-east-1");

    ConnectionIdentifierShape shape = ConnectionIdentifierShape.captureFromTomlConfig(config);

    assertTrue(shape.isAccountProvided());
    assertTrue(shape.isAccountWithRegion());
    assertFalse(
        shape.isAccountOrgProvided(),
        "region-tail dashes inside 'us-east-1' must not be counted as org separator");
    assertFalse(shape.isHostProvided());
  }

  @Test
  public void tomlPath_orgPrefixedAccount() {
    Map<String, String> config = new HashMap<>();
    config.put("account", "myorg-myacct");

    ConnectionIdentifierShape shape = ConnectionIdentifierShape.captureFromTomlConfig(config);

    assertTrue(shape.isAccountProvided());
    assertFalse(shape.isAccountWithRegion());
    assertTrue(shape.isAccountOrgProvided());
  }

  @Test
  public void tomlPath_orgPrefixedAccount_withDottedRegion() {
    Map<String, String> config = new HashMap<>();
    config.put("account", "myorg-myacct.us-east-1");

    ConnectionIdentifierShape shape = ConnectionIdentifierShape.captureFromTomlConfig(config);

    assertTrue(shape.isAccountProvided());
    assertTrue(shape.isAccountWithRegion());
    assertTrue(shape.isAccountOrgProvided());
  }

  @Test
  public void tomlPath_hostOnly() {
    Map<String, String> config = new HashMap<>();
    config.put("host", "myacct.snowflakecomputing.com");

    ConnectionIdentifierShape shape = ConnectionIdentifierShape.captureFromTomlConfig(config);

    assertFalse(shape.isAccountProvided());
    assertTrue(shape.isHostProvided());
  }

  @Test
  public void tomlPath_accountAndHost() {
    Map<String, String> config = new HashMap<>();
    config.put("account", "myacct");
    config.put("host", "myacct.us-east-1.aws.snowflakecomputing.com");

    ConnectionIdentifierShape shape = ConnectionIdentifierShape.captureFromTomlConfig(config);

    assertTrue(shape.isAccountProvided());
    assertTrue(shape.isHostProvided());
  }

  @Test
  public void tomlPath_emptyConfig_givesEmptyShape() {
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromTomlConfig(new HashMap<>());

    assertFalse(shape.isAccountProvided());
    assertFalse(shape.isHostProvided());
    assertFalse(shape.isRegionProvided());
  }

  @Test
  public void tomlPath_nullConfig_givesEmptyShape() {
    ConnectionIdentifierShape shape = ConnectionIdentifierShape.captureFromTomlConfig(null);

    assertFalse(shape.isAccountProvided());
    assertFalse(shape.isHostProvided());
    assertFalse(shape.isRegionProvided());
  }

  @Test
  public void tomlPath_emptyStrings_areNotProvided() {
    Map<String, String> config = new HashMap<>();
    config.put("account", "");
    config.put("host", "");

    ConnectionIdentifierShape shape = ConnectionIdentifierShape.captureFromTomlConfig(config);

    assertFalse(shape.isAccountProvided());
    assertFalse(shape.isHostProvided());
  }

  @Test
  public void urlPath_leadingDotAccount_runsDashSearchOverFullValue() {
    // Pathological input — the user couldn't realistically configure this, but the
    // cross-driver spec needs to be unambiguous. gosnowflake's recordAccountShape (in
    // internal/config/dsn.go) gates the dot-split on `i > 0`, so a leading dot leaves the
    // full string as the "account portion" and the dash search runs over the whole value.
    // The Python and Node.js siblings were aligned to this; JDBC matches because
    // captureFromUrlAndProperties checks `dot > 0` for the same split.
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(
            URL + "?account=.us-east-1", new Properties());

    assertTrue(shape.isAccountProvided());
    assertFalse(
        shape.isAccountWithRegion(),
        "leading dot must not credit account_with_region — there is no real account/region split");
    assertTrue(
        shape.isAccountOrgProvided(),
        "dash search runs over the full raw value when there is no real split");
  }

  @Test
  public void urlPath_trailingDotAccount_creditsAccountWithRegion() {
    // Single trailing dot — splits into "myacct" + "" — the region tail is empty but the
    // split semantics still hold. Mirrors gosnowflake, which sets AccountWithRegion=true for
    // any dotIndex > 0 regardless of whether the region portion has content.
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(
            URL + "?account=myacct.", new Properties());

    assertTrue(shape.isAccountProvided());
    assertTrue(shape.isAccountWithRegion());
    assertFalse(shape.isAccountOrgProvided());
  }

  @Test
  public void tomlPath_leadingDotAccount_runsDashSearchOverFullValue() {
    // TOML-path mirror of urlPath_leadingDotAccount_runsDashSearchOverFullValue. Cross-driver
    // parity check — Go's recordAccountShape doesn't care which path the raw account string
    // came from; neither must the JDBC equivalent.
    Map<String, String> config = new HashMap<>();
    config.put("account", ".us-east-1");

    ConnectionIdentifierShape shape = ConnectionIdentifierShape.captureFromTomlConfig(config);

    assertTrue(shape.isAccountProvided());
    assertFalse(shape.isAccountWithRegion());
    assertTrue(shape.isAccountOrgProvided());
  }

  @Test
  public void tomlPath_trailingDotAccount_creditsAccountWithRegion() {
    Map<String, String> config = new HashMap<>();
    config.put("account", "myacct.");

    ConnectionIdentifierShape shape = ConnectionIdentifierShape.captureFromTomlConfig(config);

    assertTrue(shape.isAccountProvided());
    assertTrue(shape.isAccountWithRegion());
    assertFalse(shape.isAccountOrgProvided());
  }

  @Test
  public void empty_isAllFalse() {
    ConnectionIdentifierShape shape = ConnectionIdentifierShape.empty();

    assertFalse(shape.isAccountProvided());
    assertFalse(shape.isAccountWithRegion());
    assertFalse(shape.isAccountOrgProvided());
    assertFalse(shape.isRegionProvided());
    assertFalse(shape.isHostProvided());
  }

  @Test
  public void urlPath_caseSensitiveAccountQueryKey() {
    // Snowflake's URL parser treats "account" key case-insensitively; mirror that.
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(
            URL + "?Account=myacct", new Properties());

    assertTrue(shape.isAccountProvided());
  }

  @Test
  public void urlPath_accountKeyOrderInQuery() {
    ConnectionIdentifierShape shape =
        ConnectionIdentifierShape.captureFromUrlAndProperties(
            URL + "?warehouse=wh&account=myacct&db=mydb", new Properties());

    assertTrue(shape.isAccountProvided());
    assertEquals(false, shape.isAccountWithRegion());
  }
}
