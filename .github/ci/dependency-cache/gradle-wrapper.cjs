'use strict';

function brokerProperties(properties, origin) {
  const distribution = /^distributionUrl=https\\?:\/\/services\.gradle\.org\/distributions\/(gradle-[a-zA-Z0-9.+-]+\.zip)$/m;
  if (!distribution.test(properties)) throw new Error('Unsupported Gradle wrapper distribution source');
  const routed = properties.replace(distribution, `distributionUrl=${origin}/raw/gradle/$1`);
  // Cold playback can verify the entire distribution before sending its first byte.
  const timeout = /^networkTimeout=(\d+)$/m;
  if (!timeout.test(routed)) return `${routed.trimEnd()}\nnetworkTimeout=120000\n`;
  return routed.replace(timeout, (_, value) => `networkTimeout=${Math.max(120000, Number(value))}`);
}

module.exports = {brokerProperties};
