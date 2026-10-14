package com.neoalive.tacz_sewv.config;

/**
 * Headless registry sanity check. Run via {@code ./gradlew selfCheckConfig}.
 */
public final class ConfigRegistrySelfCheck {

  private static final int MIN_ENTRIES = 250;

  public static void main(String[] args) {
    ConfigRegistry.bootstrap();
    int count = ConfigRegistry.entryCount();
    assert count >= MIN_ENTRIES : "ConfigRegistry too small: " + count + " < " + MIN_ENTRIES;

    int idx = 0;
    for (ConfigEntry e : ConfigRegistry.entries()) {
      assert e.index == idx : "Index gap at " + e.key + ": expected " + idx + " got " + e.index;
      assert ConfigRegistry.byKey(e.key) == e;
      assert ConfigRegistry.byIndex(e.index) == e;
      if (e.type != ConfigValueType.SHORTCUT && e.type != ConfigValueType.GAMERULE_BOOL) {
        assert e.isWritable() : "Non-writable entry: " + e.key;
      }
      idx++;
    }

    assert !ConfigRegistry.categoriesForScope(ConfigScope.CLIENT).isEmpty();
    assert !ConfigRegistry.categoriesForScope(ConfigScope.SERVER).isEmpty();

    keyMigration();

    System.out.println("ConfigRegistrySelfCheck OK — " + count + " entries");
  }

  /**
   * ConfigKeyMigration: every rename lands on a key the spec really has (a typo would migrate a
   * value into a key Forge then deletes), the old key does not, and an old value moves while a
   * value already under the new key is never overwritten.
   */
  private static void keyMigration() {
    var spec = SewvConfig.SPEC.getSpec();
    for (var e : ConfigKeyMigration.renames().entrySet()) {
      assert spec.contains(e.getValue()) : "rename target missing from spec: " + e.getValue();
      assert !spec.contains(e.getKey()) : "renamed-away key still in spec: " + e.getKey();
    }
    var cfg = com.electronwill.nightconfig.core.CommentedConfig.inMemory();
    cfg.set("events.tankSpawnChanceRu", 0.5);
    cfg.set("events.tankSpawnChanceUs", 0.25);
    cfg.set("events.groundSpawnChanceUs", 0.75); // already migrated by hand: must win
    int moved = ConfigKeyMigration.apply(cfg);
    assert moved == 1 : "expected one move, got " + moved;
    assert ((Number) cfg.get("events.groundSpawnChanceRu")).doubleValue() == 0.5 : "RU value carried";
    assert ((Number) cfg.get("events.groundSpawnChanceUs")).doubleValue() == 0.75 : "existing new key kept";
    assert !cfg.contains("events.tankSpawnChanceRu") && !cfg.contains("events.tankSpawnChanceUs") : "old keys removed";
  }

  private ConfigRegistrySelfCheck() {}
}
