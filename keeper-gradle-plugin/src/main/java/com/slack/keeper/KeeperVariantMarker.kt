package com.slack.keeper

import com.android.build.api.variant.VariantBuilder

/**
 * Register this with [VariantBuilder.registerExtension] to opt this variant into
 * Keeper.
 *
 * ```
 * androidComponents {
 *   beforeVariants { variantBuilder ->
 *     if (shouldRunKeeperOnVariant()) {
 *       variantBuilder.registerExtension(KeeperVariantMarker.class, KeeperVariantMarker)
 *     }
 *   }
 * }
 * ```
 */
public object KeeperVariantMarker

/** Shorthand to register Keeper on this variant. */
public fun VariantBuilder.optInToKeeper() {
  registerExtension(KeeperVariantMarker::class.java, KeeperVariantMarker)
}