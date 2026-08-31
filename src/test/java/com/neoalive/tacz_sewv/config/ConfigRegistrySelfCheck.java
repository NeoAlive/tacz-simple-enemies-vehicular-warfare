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

    System.out.println("ConfigRegistrySelfCheck OK — " + count + " entries");
  }

  private ConfigRegistrySelfCheck() {}
}
