package com.aymandev.universalresources;

import com.ldtteam.structurize.placement.handlers.placement.PlacementHandlers;
import java.util.ArrayList;

public class PlacementHandlerOverrider {
  public static void init() {
    var handlers = new ArrayList<>(PlacementHandlers.handlers);

    for (var idx = 0; idx < handlers.size(); idx++) {
      PlacementHandlers.handlers.set(idx, new WrappedPlacementHandler(handlers.get(idx)));
    }
  }
}
