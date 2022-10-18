/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.common

import java.io.IOException
import java.util.{Collection => JCollection, Collections, HashMap => JHashMap, Map => JMap}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import scala.collection.JavaConverters._
import scala.util.Try

import org.apache.celeborn.common.identity.DefaultIdentityProvider
import org.apache.celeborn.common.internal.Logging
import org.apache.celeborn.common.internal.config._
import org.apache.celeborn.common.network.util.ByteUnit
import org.apache.celeborn.common.protocol.{PartitionSplitMode, PartitionType, StorageInfo}
import org.apache.celeborn.common.protocol.StorageInfo.Type
import org.apache.celeborn.common.protocol.StorageInfo.Type.{HDD, SSD}
import org.apache.celeborn.common.quota.DefaultQuotaManager
import org.apache.celeborn.common.util.Utils

class RssConf(loadDefaults: Boolean) extends Cloneable with Logging with Serializable {

  import RssConf._

  /** Create a RssConf that loads defaults from system properties and the classpath */
  def this() = this(true)

  private val settings = new ConcurrentHashMap[String, String]()

  @transient private lazy val reader: ConfigReader = {
    val _reader = new ConfigReader(new RssConfigProvider(settings))
    _reader.bindEnv(new ConfigProvider {
      override def get(key: String): Option[String] = Option(getenv(key))
    })
    _reader
  }

  private def loadFromMap(props: Map[String, String], silent: Boolean): Unit =
    settings.synchronized {
      // Load any rss.* system properties
      for ((key, value) <- props if key.startsWith("celeborn.") || key.startsWith("rss.")) {
        set(key, value, silent)
      }
      this
    }

  if (loadDefaults) {
    loadFromMap(Utils.getSystemProperties, false)
  }

  /** Set a configuration variable. */
  def set(key: String, value: String): RssConf = {
    set(key, value, false)
  }

  private[celeborn] def set(key: String, value: String, silent: Boolean): RssConf = {
    if (key == null) {
      throw new NullPointerException("null key")
    }
    if (value == null) {
      throw new NullPointerException(s"null value for $key")
    }
    if (!silent) {
      logDeprecationWarning(key)
    }
    requireDefaultValueOfRemovedConf(key, value)
    settings.put(key, value)
    this
  }

  def set[T](entry: ConfigEntry[T], value: T): RssConf = {
    set(entry.key, entry.stringConverter(value))
    this
  }

  def set[T](entry: OptionalConfigEntry[T], value: T): RssConf = {
    set(entry.key, entry.rawStringConverter(value))
    this
  }

  /** Set multiple parameters together */
  def setAll(settings: Traversable[(String, String)]): RssConf = {
    settings.foreach { case (k, v) => set(k, v) }
    this
  }

  /** Set a parameter if it isn't already configured */
  def setIfMissing(key: String, value: String): RssConf = {
    requireDefaultValueOfRemovedConf(key, value)
    if (settings.putIfAbsent(key, value) == null) {
      logDeprecationWarning(key)
    }
    this
  }

  def setIfMissing[T](entry: ConfigEntry[T], value: T): RssConf = {
    setIfMissing(entry.key, entry.stringConverter(value))
  }

  def setIfMissing[T](entry: OptionalConfigEntry[T], value: T): RssConf = {
    setIfMissing(entry.key, entry.rawStringConverter(value))
  }

  /** Remove a parameter from the configuration */
  def unset(key: String): RssConf = {
    settings.remove(key)
    this
  }

  def unset(entry: ConfigEntry[_]): RssConf = {
    unset(entry.key)
  }

  def clear(): Unit = {
    settings.clear()
  }

  /** Get a parameter; throws a NoSuchElementException if it's not set */
  def get(key: String): String = {
    getOption(key).getOrElse(throw new NoSuchElementException(key))
  }

  /** Get a parameter, falling back to a default if not set */
  def get(key: String, defaultValue: String): String = {
    getOption(key).getOrElse(defaultValue)
  }

  def get[T](entry: ConfigEntry[T]): T = {
    entry.readFrom(reader)
  }

  /**
   * Get a time parameter as seconds; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then seconds are assumed.
   * @throws java.util.NoSuchElementException If the time parameter is not set
   * @throws NumberFormatException            If the value cannot be interpreted as seconds
   */
  def getTimeAsSeconds(key: String): Long = catchIllegalValue(key) {
    Utils.timeStringAsSeconds(get(key))
  }

  /**
   * Get a time parameter as seconds, falling back to a default if not set. If no
   * suffix is provided then seconds are assumed.
   * @throws NumberFormatException If the value cannot be interpreted as seconds
   */
  def getTimeAsSeconds(key: String, defaultValue: String): Long = catchIllegalValue(key) {
    Utils.timeStringAsSeconds(get(key, defaultValue))
  }

  /**
   * Get a time parameter as milliseconds; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then milliseconds are assumed.
   * @throws java.util.NoSuchElementException If the time parameter is not set
   * @throws NumberFormatException If the value cannot be interpreted as milliseconds
   */
  def getTimeAsMs(key: String): Long = catchIllegalValue(key) {
    Utils.timeStringAsMs(get(key))
  }

  /**
   * Get a time parameter as milliseconds, falling back to a default if not set. If no
   * suffix is provided then milliseconds are assumed.
   * @throws NumberFormatException If the value cannot be interpreted as milliseconds
   */
  def getTimeAsMs(key: String, defaultValue: String): Long = catchIllegalValue(key) {
    Utils.timeStringAsMs(get(key, defaultValue))
  }

  /**
   * Get a size parameter as bytes; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then bytes are assumed.
   * @throws java.util.NoSuchElementException If the size parameter is not set
   * @throws NumberFormatException If the value cannot be interpreted as bytes
   */
  def getSizeAsBytes(key: String): Long = catchIllegalValue(key) {
    Utils.byteStringAsBytes(get(key))
  }

  /**
   * Get a size parameter as bytes, falling back to a default if not set. If no
   * suffix is provided then bytes are assumed.
   * @throws NumberFormatException If the value cannot be interpreted as bytes
   */
  def getSizeAsBytes(key: String, defaultValue: String): Long = catchIllegalValue(key) {
    Utils.byteStringAsBytes(get(key, defaultValue))
  }

  /**
   * Get a size parameter as bytes, falling back to a default if not set.
   * @throws NumberFormatException If the value cannot be interpreted as bytes
   */
  def getSizeAsBytes(key: String, defaultValue: Long): Long = catchIllegalValue(key) {
    Utils.byteStringAsBytes(get(key, defaultValue + "B"))
  }

  /**
   * Get a size parameter as Kibibytes; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then Kibibytes are assumed.
   * @throws java.util.NoSuchElementException If the size parameter is not set
   * @throws NumberFormatException If the value cannot be interpreted as Kibibytes
   */
  def getSizeAsKb(key: String): Long = catchIllegalValue(key) {
    Utils.byteStringAsKb(get(key))
  }

  /**
   * Get a size parameter as Kibibytes, falling back to a default if not set. If no
   * suffix is provided then Kibibytes are assumed.
   * @throws NumberFormatException If the value cannot be interpreted as Kibibytes
   */
  def getSizeAsKb(key: String, defaultValue: String): Long = catchIllegalValue(key) {
    Utils.byteStringAsKb(get(key, defaultValue))
  }

  /**
   * Get a size parameter as Mebibytes; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then Mebibytes are assumed.
   * @throws java.util.NoSuchElementException If the size parameter is not set
   * @throws NumberFormatException If the value cannot be interpreted as Mebibytes
   */
  def getSizeAsMb(key: String): Long = catchIllegalValue(key) {
    Utils.byteStringAsMb(get(key))
  }

  /**
   * Get a size parameter as Mebibytes, falling back to a default if not set. If no
   * suffix is provided then Mebibytes are assumed.
   * @throws NumberFormatException If the value cannot be interpreted as Mebibytes
   */
  def getSizeAsMb(key: String, defaultValue: String): Long = catchIllegalValue(key) {
    Utils.byteStringAsMb(get(key, defaultValue))
  }

  /**
   * Get a size parameter as Gibibytes; throws a NoSuchElementException if it's not set. If no
   * suffix is provided then Gibibytes are assumed.
   * @throws java.util.NoSuchElementException If the size parameter is not set
   * @throws NumberFormatException If the value cannot be interpreted as Gibibytes
   */
  def getSizeAsGb(key: String): Long = catchIllegalValue(key) {
    Utils.byteStringAsGb(get(key))
  }

  /**
   * Get a size parameter as Gibibytes, falling back to a default if not set. If no
   * suffix is provided then Gibibytes are assumed.
   * @throws NumberFormatException If the value cannot be interpreted as Gibibytes
   */
  def getSizeAsGb(key: String, defaultValue: String): Long = catchIllegalValue(key) {
    Utils.byteStringAsGb(get(key, defaultValue))
  }

  /** Get a parameter as an Option */
  def getOption(key: String): Option[String] = {
    Option(settings.get(key)).orElse(getDeprecatedConfig(key, settings))
  }

  /** Get an optional value, applying variable substitution. */
  private[celeborn] def getWithSubstitution(key: String): Option[String] = {
    getOption(key).map(reader.substitute)
  }

  /** Get all parameters as a list of pairs */
  def getAll: Array[(String, String)] = {
    settings.entrySet().asScala.map(x => (x.getKey, x.getValue)).toArray
  }

  /**
   * Get all parameters that start with `prefix`
   */
  def getAllWithPrefix(prefix: String): Array[(String, String)] = {
    getAll.filter { case (k, v) => k.startsWith(prefix) }
      .map { case (k, v) => (k.substring(prefix.length), v) }
  }

  /**
   * Get a parameter as an integer, falling back to a default if not set
   * @throws NumberFormatException If the value cannot be interpreted as an integer
   */
  def getInt(key: String, defaultValue: Int): Int = catchIllegalValue(key) {
    getOption(key).map(_.toInt).getOrElse(defaultValue)
  }

  /**
   * Get a parameter as a long, falling back to a default if not set
   * @throws NumberFormatException If the value cannot be interpreted as a long
   */
  def getLong(key: String, defaultValue: Long): Long = catchIllegalValue(key) {
    getOption(key).map(_.toLong).getOrElse(defaultValue)
  }

  /**
   * Get a parameter as a double, falling back to a default if not ste
   * @throws NumberFormatException If the value cannot be interpreted as a double
   */
  def getDouble(key: String, defaultValue: Double): Double = catchIllegalValue(key) {
    getOption(key).map(_.toDouble).getOrElse(defaultValue)
  }

  /**
   * Get a parameter as a boolean, falling back to a default if not set
   * @throws IllegalArgumentException If the value cannot be interpreted as a boolean
   */
  def getBoolean(key: String, defaultValue: Boolean): Boolean = catchIllegalValue(key) {
    getOption(key).map(_.toBoolean).getOrElse(defaultValue)
  }

  /** Does the configuration contain a given parameter? */
  def contains(key: String): Boolean = {
    settings.containsKey(key) ||
    configsWithAlternatives.get(key).toSeq.flatten.exists { alt => contains(alt.key) }
  }

  private[celeborn] def contains(entry: ConfigEntry[_]): Boolean = contains(entry.key)

  /** Copy this object */
  override def clone: RssConf = {
    val cloned = new RssConf(false)
    settings.entrySet().asScala.foreach { e =>
      cloned.set(e.getKey, e.getValue, true)
    }
    cloned
  }

  /**
   * By using this instead of System.getenv(), environment variables can be mocked
   * in unit tests.
   */
  private[celeborn] def getenv(name: String): String = System.getenv(name)

  /**
   * Wrapper method for get() methods which require some specific value format. This catches
   * any [[NumberFormatException]] or [[IllegalArgumentException]] and re-raises it with the
   * incorrectly configured key in the exception message.
   */
  private def catchIllegalValue[T](key: String)(getValue: => T): T = {
    try {
      getValue
    } catch {
      case e: NumberFormatException =>
        // NumberFormatException doesn't have a constructor that takes a cause for some reason.
        throw new NumberFormatException(s"Illegal value for config key $key: ${e.getMessage}")
          .initCause(e)
      case e: IllegalArgumentException =>
        throw new IllegalArgumentException(s"Illegal value for config key $key: ${e.getMessage}", e)
    }
  }
}

object RssConf extends Logging {

  /**
   * Holds information about keys that have been deprecated and do not have a replacement.
   *
   * @param key                The deprecated key.
   * @param version            The version in which the key was deprecated.
   * @param deprecationMessage Message to include in the deprecation warning.
   */
  private case class DeprecatedConfig(
      key: String,
      version: String,
      deprecationMessage: String)

  /**
   * Information about an alternate configuration key that has been deprecated.
   *
   * @param key         The deprecated config key.
   * @param version     The version in which the key was deprecated.
   * @param translation A translation function for converting old config values into new ones.
   */
  private case class AlternateConfig(
      key: String,
      version: String,
      translation: String => String = null)

  /**
   * Holds information about keys that have been removed.
   *
   * @param key          The removed config key.
   * @param version      The version in which key was removed.
   * @param defaultValue The default config value. It can be used to notice
   *                     users that they set non-default value to an already removed config.
   * @param comment      Additional info regarding to the removed config.
   */
  case class RemovedConfig(key: String, version: String, defaultValue: String, comment: String)

  /**
   * Maps deprecated config keys to information about the deprecation.
   *
   * The extra information is logged as a warning when the config is present in the user's
   * configuration.
   */
  private val deprecatedConfigs: Map[String, DeprecatedConfig] = {
    val configs = Seq(
      DeprecatedConfig("none", "1.0", "None"))

    Map(configs.map { cfg => (cfg.key -> cfg) }: _*)
  }

  /**
   * The map contains info about removed SQL configs. Keys are SQL config names,
   * map values contain extra information like the version in which the config was removed,
   * config's default value and a comment.
   *
   * Please, add a removed configuration property here only when it affects behaviours.
   * By this, it makes migrations to new versions painless.
   */
  val removedConfigs: Map[String, RemovedConfig] = {
    val masterEndpointsTips = "The behavior is controlled by `celeborn.master.endpoints` now, " +
      "please check the documentation for details."
    val configs = Seq(
      RemovedConfig("rss.ha.master.hosts", "0.2.0", null, masterEndpointsTips),
      RemovedConfig("rss.ha.service.id", "0.2.0", "rss", "configuration key removed."),
      RemovedConfig("rss.ha.nodes.rss", "0.2.0", "1,2,3,", "configuration key removed."))
    Map(configs.map { cfg => cfg.key -> cfg }: _*)
  }

  /**
   * Maps a current config key to alternate keys that were used in previous version.
   *
   * The alternates are used in the order defined in this map. If deprecated configs are
   * present in the user's configuration, a warning is logged.
   */
  private val configsWithAlternatives = Map[String, Seq[AlternateConfig]](
    "none" -> Seq(
      AlternateConfig("none", "1.0")))

  /**
   * A view of `configsWithAlternatives` that makes it more efficient to look up deprecated
   * config keys.
   *
   * Maps the deprecated config name to a 2-tuple (new config name, alternate config info).
   */
  private val allAlternatives: Map[String, (String, AlternateConfig)] = {
    configsWithAlternatives.keys.flatMap { key =>
      configsWithAlternatives(key).map { cfg => (cfg.key -> (key -> cfg)) }
    }.toMap
  }

  /**
   * Looks for available deprecated keys for the given config option, and return the first
   * value available.
   */
  def getDeprecatedConfig(key: String, conf: JMap[String, String]): Option[String] = {
    configsWithAlternatives.get(key).flatMap { alts =>
      alts.collectFirst {
        case alt if conf.containsKey(alt.key) =>
          val value = conf.get(alt.key)
          if (alt.translation != null) alt.translation(value) else value
      }
    }
  }

  private def requireDefaultValueOfRemovedConf(key: String, value: String): Unit = {
    removedConfigs.get(key).foreach {
      case RemovedConfig(configName, version, defaultValue, comment) =>
        if (value != defaultValue) {
          throw new IllegalArgumentException(
            s"The config '$configName' was removed in v$version. $comment")
        }
    }
  }

  /**
   * Logs a warning message if the given config key is deprecated.
   */
  private def logDeprecationWarning(key: String): Unit = {
    deprecatedConfigs.get(key).foreach { cfg =>
      logWarning(
        s"The configuration key '$key' has been deprecated in v${cfg.version} and " +
          s"may be removed in the future. ${cfg.deprecationMessage}")
      return
    }

    allAlternatives.get(key).foreach { case (newKey, cfg) =>
      logWarning(
        s"The configuration key '$key' has been deprecated in v${cfg.version} and " +
          s"may be removed in the future. Please use the new key '$newKey' instead.")
      return
    }
  }

  private[this] val rssConfEntriesUpdateLock = new Object

  @volatile
  private[this] var rssConfEntries: JMap[String, ConfigEntry[_]] = Collections.emptyMap()

  private def register(entry: ConfigEntry[_]): Unit = rssConfEntriesUpdateLock.synchronized {
    require(
      !rssConfEntries.containsKey(entry.key),
      s"Duplicate RssConfigEntry. ${entry.key} has been registered")
    val updatedMap = new JHashMap[String, ConfigEntry[_]](rssConfEntries)
    updatedMap.put(entry.key, entry)
    rssConfEntries = updatedMap
  }

  private[celeborn] def unregister(entry: ConfigEntry[_]): Unit =
    rssConfEntriesUpdateLock.synchronized {
      val updatedMap = new JHashMap[String, ConfigEntry[_]](rssConfEntries)
      updatedMap.remove(entry.key)
      rssConfEntries = updatedMap
    }

  private[celeborn] def getConfigEntry(key: String): ConfigEntry[_] = {
    rssConfEntries.get(key)
  }

  private[celeborn] def getConfigEntries: JCollection[ConfigEntry[_]] = {
    rssConfEntries.values()
  }

  private[celeborn] def containsConfigEntry(entry: ConfigEntry[_]): Boolean = {
    getConfigEntry(entry.key) == entry
  }

  private[celeborn] def containsConfigKey(key: String): Boolean = {
    rssConfEntries.containsKey(key)
  }

  def buildConf(key: String): ConfigBuilder = ConfigBuilder(key).onCreate(register)

  val MASTER_ENDPOINTS: ConfigEntry[Seq[String]] =
    buildConf("celeborn.master.endpoints")
      .categories("client", "worker")
      .doc("Endpoints of master nodes for celeborn client to connect, allowed pattern " +
        "is: `<host1>:<port1>[,<host2>:<port2>]*`, e.g. `clb1:9097,clb2:9098,clb3:9099`. " +
        "If the port is omitted, 9097 will be used.")
      .version("0.2.0")
      .stringConf
      .transform(_.replace("<localhost>", Utils.localHostName))
      .toSequence
      .checkValue(
        endpoints => endpoints.map(_ => Try(Utils.parseHostPort(_))).forall(_.isSuccess),
        "Allowed pattern is: `<host1>:<port1>[,<host2>:<port2>]*`")
      .createWithDefaultString(s"<localhost>:9097")

  val SHUFFLE_WRITER_MODE: ConfigEntry[String] =
    buildConf("celeborn.shuffle.writer.mode")
      .withAlternative("rss.shuffle.writer.mode")
      .categories("client")
      .doc("Celeborn supports the following kind of shuffle writers. 1. hash: hash-based shuffle writer " +
        "works fine when shuffle partition count is normal; 2. sort: sort-based shuffle writer works fine " +
        "when memory pressure is high or shuffle partition count it huge.")
      .version("0.2.0")
      .stringConf
      .transform(_.toLowerCase)
      .checkValue(v => v == "hash" || v == "sort", "invalid value, options: 'hash', 'sort'")
      .createWithDefault("hash")

  val PUSH_REPLICATE_ENABLED: ConfigEntry[Boolean] =
    buildConf("celeborn.push.replicate.enabled")
      .withAlternative("rss.push.data.replicate")
      .categories("client")
      .doc("When true, Celeborn worker will replicate shuffle data to another Celeborn worker " +
        "asynchronously to ensure the pushed shuffle data won't be lost after the node failure.")
      .version("0.2.0")
      .booleanConf
      .createWithDefault(true)

  val PUSH_BUFFER_INITIAL_SIZE: ConfigEntry[Long] =
    buildConf("celeborn.push.buffer.initial.size")
      .withAlternative("rss.push.data.buffer.initial.size")
      .categories("client")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("8k")

  val PUSH_BUFFER_MAX_SIZE: ConfigEntry[Long] =
    buildConf("celeborn.push.buffer.max.size")
      .withAlternative("rss.push.data.buffer.size")
      .categories("client")
      .doc("Max size of reducer partition buffer memory. The pushed data will be buffered in " +
        "memory before sending to Celeborn worker. For performance consideration keep this buffer " +
        "size higher than 32K. Example: If reducer amount is 2000, buffer size is 64K, then each " +
        "task will consume up to `64KiB * 2000 = 125MiB` heap memory.")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("64k")

  val PUSH_QUEUE_CAPACITY: ConfigEntry[Int] =
    buildConf("celeborn.push.queue.capacity")
      .withAlternative("rss.push.data.queue.capacity")
      .categories("client")
      .doc("Push buffer queue size for a task. The maximum memory is " +
        "`celeborn.push.buffer.max.size` * `celeborn.push.queue.capacity`, " +
        "default: 64KiB * 512 = 32MiB")
      .intConf
      .createWithDefault(512)

  val PUSH_MAX_REQS_IN_FLIGHT: ConfigEntry[Int] =
    buildConf("celeborn.push.maxReqsInFlight")
      .withAlternative("rss.push.data.maxReqsInFlight")
      .categories("client")
      .doc("Amount of Netty in-flight requests. The maximum memory is " +
        "`celeborn.push.maxReqsInFlight` * `celeborn.push.buffer.max.size` * " +
        "compression ratio(1 in worst case), default: 64Kib * 32 = 2Mib")
      .intConf
      .createWithDefault(32)

  def pushReplicateEnabled(conf: RssConf): Boolean = conf.get(PUSH_REPLICATE_ENABLED)

  def pushBufferInitialSize(conf: RssConf): Int = conf.get(PUSH_BUFFER_INITIAL_SIZE).toInt

  def pushBufferMaxSize(conf: RssConf): Int = conf.get(PUSH_BUFFER_MAX_SIZE).toInt

  def pushDataQueueCapacity(conf: RssConf): Int = conf.get(PUSH_QUEUE_CAPACITY)

  def pushDataMaxReqsInFlight(conf: RssConf): Int = conf.get(PUSH_MAX_REQS_IN_FLIGHT)

  def fetchChunkTimeoutMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.fetch.chunk.timeout", "120s")
  }

  def fetchChunkMaxReqsInFlight(conf: RssConf): Int = {
    conf.getInt("rss.fetch.chunk.maxReqsInFlight", 3)
  }

  def workerTimeoutMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.worker.timeout", "120s")
  }

  def applicationTimeoutMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.application.timeout", "120s")
  }

  def applicationHeatbeatIntervalMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.application.heartbeatInterval", "10s")
  }

  def removeShuffleDelayMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.remove.shuffle.delay", "60s")
  }

  def getBlacklistDelayMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.get.blacklist.delay", "30s")
  }

  val MASTER_HOST: ConfigEntry[String] =
    buildConf("celeborn.master.host")
      .categories("master")
      .withAlternative("rss.master.host")
      .version("0.2.0")
      .doc("Hostname for master to bind.")
      .stringConf
      .transform(_.replace("<localhost>", Utils.localHostName))
      .createWithDefaultString("<localhost>")

  val MASTER_PORT: ConfigEntry[Int] =
    buildConf("celeborn.master.port")
      .withAlternative("rss.master.port")
      .categories("master")
      .version("0.2.0")
      .doc("Port for master to bind.")
      .intConf
      .checkValue(p => p >= 1024 && p < 65535, "invalid port")
      .createWithDefault(9097)

  val HA_ENABLED: ConfigEntry[Boolean] = buildConf("celeborn.ha.enabled")
    .withAlternative("rss.ha.enabled")
    .categories("master")
    .doc("When true, master nodes run as Raft cluster mode.")
    .version("0.1.0")
    .booleanConf
    .createWithDefault(false)

  val HA_MASTER_NODE_ID: OptionalConfigEntry[String] =
    buildConf("celeborn.ha.master.node.id")
      .doc("Node id for master raft cluster in HA mode, if not define, " +
        "will be inferred by hostname.")
      .version("0.2.0")
      .stringConf
      .createOptional

  val HA_MASTER_NODE_HOST: ConfigEntry[String] =
    buildConf("celeborn.ha.master.node.<id>.host")
      .categories("master")
      .doc("Host to bind of master node <id> in HA mode.")
      .version("0.2.0")
      .stringConf
      .createWithDefaultString("<required>")

  val HA_MASTER_NODE_PORT: ConfigEntry[Int] =
    buildConf("celeborn.ha.master.node.<id>.port")
      .categories("master")
      .doc("Port to bind of master node <id> in HA mode.")
      .version("0.2.0")
      .intConf
      .checkValue(p => p >= 1024 && p < 65535, "invalid port")
      .createWithDefault(9097)

  val HA_MASTER_NODE_RATIS_HOST: OptionalConfigEntry[String] =
    buildConf("celeborn.ha.master.node.<id>.ratis.host")
      .internal
      .categories("master")
      .doc("Ratis host to bind of master node <id> in HA mode. If not provided, " +
        s"fallback to ${HA_MASTER_NODE_HOST.key}.")
      .version("0.2.0")
      .stringConf
      .createOptional

  val HA_MASTER_NODE_RATIS_PORT: ConfigEntry[Int] =
    buildConf("celeborn.ha.master.node.<id>.ratis.port")
      .categories("master")
      .doc("Ratis port to bind of master node <id> in HA mode.")
      .version("0.2.0")
      .intConf
      .checkValue(p => p >= 1024 && p < 65535, "invalid port")
      .createWithDefault(9872)

  val HA_MASTER_RATIS_RPC_TYPE: ConfigEntry[String] =
    buildConf("celeborn.ha.master.ratis.raft.rpc.type")
      .withAlternative("rss.ha.rpc.type")
      .categories("master")
      .doc("RPC type for Ratis, available options: netty, grpc.")
      .version("0.2.0")
      .stringConf
      .transform(_.toLowerCase)
      .checkValue(v => v == "netty" || v == "grpc", "illegal value, available options: netty, grpc")
      .createWithDefault("netty")

  val HA_MASTER_RATIS_STORAGE_DIR: ConfigEntry[String] =
    buildConf("celeborn.ha.master.ratis.raft.server.storage.dir")
      .categories("master")
      .withAlternative("rss.ha.storage.dir")
      .version("0.2.0")
      .stringConf
      .createWithDefault("/tmp/ratis")

  val HA_MASTER_RATIS_LOG_SEGMENT_SIZE_MAX: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.log.segment.size.max")
      .withAlternative("rss.ha.ratis.segment.size")
      .internal
      .categories("master")
      .version("0.2.0")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("4MB")

  val HA_MASTER_RATIS_LOG_PREALLOCATED_SIZE: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.log.preallocated.size")
      .withAlternative("rss.ratis.segment.preallocated.size")
      .internal
      .categories("master")
      .version("0.2.0")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("4MB")

  val HA_MASTER_RATIS_LOG_APPENDER_QUEUE_NUM_ELEMENTS: ConfigEntry[Int] =
    buildConf("celeborn.ha.master.ratis.raft.server.log.appender.buffer.element-limit")
      .withAlternative("rss.ratis.log.appender.queue.num-elements")
      .internal
      .categories("master")
      .version("0.2.0")
      .intConf
      .createWithDefault(1024)

  val HA_MASTER_RATIS_LOG_APPENDER_QUEUE_BYTE_LIMIT: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.log.appender.buffer.byte-limit")
      .withAlternative("rss.ratis.log.appender.queue.byte-limit")
      .internal
      .categories("master")
      .version("0.2.0")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("32MB")

  val HA_MASTER_RATIS_LOG_PURGE_GAP: ConfigEntry[Int] =
    buildConf("celeborn.ha.master.ratis.raft.server.log.purge.gap")
      .withAlternative("rss.ratis.log.purge.gap")
      .internal
      .categories("master")
      .version("0.2.0")
      .intConf
      .createWithDefault(1000000)

  val HA_MASTER_RATIS_RPC_REQUEST_TIMEOUT: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.rpc.request.timeout")
      .withAlternative("rss.ratis.server.request.timeout")
      .internal
      .categories("master")
      .version("0.2.0")
      .timeConf(TimeUnit.SECONDS)
      .createWithDefaultString("3s")

  val HA_MASTER_RATIS_SERVER_RETRY_CACHE_EXPIRY_TIME: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.retrycache.expirytime")
      .withAlternative("rss.ratis.server.retry.cache.timeout")
      .internal
      .categories("master")
      .version("0.2.0")
      .timeConf(TimeUnit.SECONDS)
      .createWithDefaultString("600s")

  val HA_MASTER_RATIS_RPC_TIMEOUT_MIN: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.rpc.timeout.min")
      .withAlternative("rss.ratis.minimum.timeout")
      .internal
      .categories("master")
      .version("0.2.0")
      .timeConf(TimeUnit.SECONDS)
      .createWithDefaultString("3s")

  val HA_MASTER_RATIS_RPC_TIMEOUT_MAX: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.rpc.timeout.max")
      .internal
      .categories("master")
      .version("0.2.0")
      .timeConf(TimeUnit.SECONDS)
      .createWithDefaultString("5s")

  val HA_MASTER_RATIS_NOTIFICATION_NO_LEADER_TIMEOUT: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.notification.no-leader.timeout")
      .internal
      .categories("master")
      .version("0.2.0")
      .timeConf(TimeUnit.SECONDS)
      .createWithDefaultString("120s")

  val HA_MASTER_RATIS_RPC_SLOWNESS_TIMEOUT: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.rpc.slowness.timeout")
      .withAlternative("rss.ratis.server.failure.timeout.duration")
      .internal
      .categories("master")
      .version("0.2.0")
      .timeConf(TimeUnit.SECONDS)
      .createWithDefaultString("120s")

  val HA_MASTER_RATIS_ROLE_CHECK_INTERVAL: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.role.check.interval")
      .withAlternative("rss.ratis.server.role.check.interval")
      .internal
      .categories("master")
      .version("0.2.0")
      .timeConf(TimeUnit.MILLISECONDS)
      .createWithDefaultString("1s")

  val HA_MASTER_RATIS_SNAPSHOT_AUTO_TRIGGER_ENABLED: ConfigEntry[Boolean] =
    buildConf("celeborn.ha.master.ratis.raft.server.snapshot.auto.trigger.enabled")
      .withAlternative("rss.ha.ratis.snapshot.auto.trigger.enabled")
      .internal
      .categories("master")
      .version("0.2.0")
      .booleanConf
      .createWithDefault(true)

  val HA_MASTER_RATIS_SNAPSHOT_AUTO_TRIGGER_THRESHOLD: ConfigEntry[Long] =
    buildConf("celeborn.ha.master.ratis.raft.server.snapshot.auto.trigger.threshold")
      .withAlternative("rss.ha.ratis.snapshot.auto.trigger.threshold")
      .internal
      .categories("master")
      .version("0.2.0")
      .longConf
      .createWithDefault(200000L)

  val HA_MASTER_RATIS_SNAPSHOT_RETENTION_FILE_NUM: ConfigEntry[Int] =
    buildConf("celeborn.ha.master.ratis.raft.server.snapshot.retention.file.num")
      .withAlternative("rss.ratis.snapshot.retention.file.num")
      .internal
      .categories("master")
      .version("0.2.0")
      .intConf
      .createWithDefault(3)

  def masterEndpoints(conf: RssConf): Array[String] =
    conf.get(MASTER_ENDPOINTS).toArray.map { endpoint =>
      Utils.parseHostPort(endpoint) match {
        case (host, 0) => s"$host:${HA_MASTER_NODE_PORT.defaultValue.get}"
        case (host, port) => s"$host:$port"
      }
    }

  def masterHost(conf: RssConf): String = conf.get(MASTER_HOST)

  def masterPort(conf: RssConf): Int = conf.get(MASTER_PORT)

  def haEnabled(conf: RssConf): Boolean = conf.get(HA_ENABLED)

  def haMasterNodeId(conf: RssConf): Option[String] = conf.get(HA_MASTER_NODE_ID)

  def haMasterNodeIds(conf: RssConf): Array[String] = {
    def extractPrefix(original: String, stop: String): String = {
      val i = original.indexOf(stop)
      assert(i >= 0, s"$original does not contain $stop")
      original.substring(0, i)
    }
    val nodeConfPrefix = extractPrefix(HA_MASTER_NODE_HOST.key, "<id>")
    conf.getAllWithPrefix(nodeConfPrefix)
      .map(_._1)
      .map(k => extractPrefix(k, "."))
      .distinct
  }

  def haMasterNodeHost(conf: RssConf, nodeId: String): String = {
    val key = HA_MASTER_NODE_HOST.key.replace("<id>", nodeId)
    conf.get(key, Utils.localHostName)
  }

  def haMasterNodePort(conf: RssConf, nodeId: String): Int = {
    val key = HA_MASTER_NODE_PORT.key.replace("<id>", nodeId)
    conf.getInt(key, HA_MASTER_NODE_PORT.defaultValue.get)
  }

  def haMasterRatisHost(conf: RssConf, nodeId: String): String = {
    val key = HA_MASTER_NODE_RATIS_HOST.key.replace("<id>", nodeId)
    val fallbackKey = HA_MASTER_NODE_HOST.key.replace("<id>", nodeId)
    conf.get(key, conf.get(fallbackKey))
  }

  def haMasterRatisPort(conf: RssConf, nodeId: String): Int = {
    val key = HA_MASTER_NODE_RATIS_PORT.key.replace("<id>", nodeId)
    conf.getInt(key, HA_MASTER_NODE_RATIS_PORT.defaultValue.get)
  }

  def haMasterRatisRpcType(conf: RssConf): String = conf.get(HA_MASTER_RATIS_RPC_TYPE)
  def haMasterRatisStorageDir(conf: RssConf): String = conf.get(HA_MASTER_RATIS_STORAGE_DIR)

  def haMasterRatisLogSegmentSizeMax(conf: RssConf): Long =
    conf.get(HA_MASTER_RATIS_LOG_SEGMENT_SIZE_MAX)

  def haMasterRatisLogPreallocatedSize(conf: RssConf): Long =
    conf.get(HA_MASTER_RATIS_LOG_PREALLOCATED_SIZE)

  def haMasterRatisLogAppenderQueueNumElements(conf: RssConf): Int =
    conf.get(HA_MASTER_RATIS_LOG_APPENDER_QUEUE_NUM_ELEMENTS)

  def haMasterRatisLogAppenderQueueBytesLimit(conf: RssConf): Long =
    conf.get(HA_MASTER_RATIS_LOG_APPENDER_QUEUE_BYTE_LIMIT)

  def haMasterRatisLogPurgeGap(conf: RssConf): Int = conf.get(HA_MASTER_RATIS_LOG_PURGE_GAP)

  def haMasterRatisRpcRequestTimeout(conf: RssConf): Long =
    conf.get(HA_MASTER_RATIS_RPC_REQUEST_TIMEOUT)

  def haMasterRatisRetryCacheExpiryTime(conf: RssConf): Long =
    conf.get(HA_MASTER_RATIS_SERVER_RETRY_CACHE_EXPIRY_TIME)

  def haMasterRatisRpcTimeoutMin(conf: RssConf): Long = conf.get(HA_MASTER_RATIS_RPC_TIMEOUT_MIN)

  def haMasterRatisRpcTimeoutMax(conf: RssConf): Long = conf.get(HA_MASTER_RATIS_RPC_TIMEOUT_MAX)

  def haMasterRatisNotificationNoLeaderTimeout(conf: RssConf): Long =
    conf.get(HA_MASTER_RATIS_NOTIFICATION_NO_LEADER_TIMEOUT)

  def haMasterRatisRpcSlownessTimeout(conf: RssConf): Long =
    conf.get(HA_MASTER_RATIS_RPC_SLOWNESS_TIMEOUT)

  def haMasterRatisRoleCheckInterval(conf: RssConf): Long =
    conf.get(HA_MASTER_RATIS_ROLE_CHECK_INTERVAL)

  def haMasterRatisSnapshotAutoTriggerEnabled(conf: RssConf): Boolean =
    conf.get(HA_MASTER_RATIS_SNAPSHOT_AUTO_TRIGGER_ENABLED)

  def haMasterRatisSnapshotAutoTriggerThreshold(conf: RssConf): Long =
    conf.get(HA_MASTER_RATIS_SNAPSHOT_AUTO_TRIGGER_THRESHOLD)
  def haMasterRatisSnapshotRetentionFileNum(conf: RssConf): Int =
    conf.get(HA_MASTER_RATIS_SNAPSHOT_RETENTION_FILE_NUM)

  val WORKER_REPLICATE_THREADS: ConfigEntry[Int] =
    buildConf("celeborn.worker.replicate.threads")
      .withAlternative("rss.worker.replicate.numThreads")
      .categories("worker")
      .doc("Thread number of worker to replicate shuffle data.")
      .intConf
      .createWithDefault(64)

  val WORKER_COMMIT_THREADS: ConfigEntry[Int] =
    buildConf("celeborn.worker.commit.threads")
      .withAlternative("rss.worker.asyncCommitFiles.numThreads")
      .categories("worker")
      .doc("Thread number of worker to commit shuffle data files asynchronously.")
      .intConf
      .createWithDefault(32)

  val WORKER_WORKING_DIR_NAME: ConfigEntry[String] =
    buildConf("celeborn.worker.working.dir.name")
      .withAlternative("rss.worker.workingDirName")
      .categories("worker")
      .doc("")
      .stringConf
      .createWithDefaultString("hadoop/rss-worker/shuffle_data")

  val WORKER_STORAGE_DIRS: OptionalConfigEntry[Seq[String]] =
    buildConf("celeborn.worker.storage.dirs")
      .withAlternative("rss.worker.base.dirs")
      .categories("worker")
      .doc("Directory list to store shuffle data. Storage size limit can be set for each " +
        "directory. For the sake of performance, there should be no more than 2 directories " +
        "on the same disk partition if you are using HDD. There can be 4 or more directories " +
        "can run on the same disk partition if you are using SSD. For example: " +
        "dir1[:capacity=][:disktype=][:flushthread=],dir2[:capacity=][:disktype=][:flushthread=]")
      .stringConf
      .toSequence
      .createOptional

  val WORKER_STORAGE_DIR_PREFIX: ConfigEntry[String] =
    buildConf("celeborn.worker.storage.dir.prefix")
      .withAlternative("rss.worker.base.dir.prefix")
      .categories("worker")
      .doc("")
      .stringConf
      .createWithDefaultString("/mnt/disk")

  val WORKER_STORAGE_DIR_NUMBER: ConfigEntry[Int] =
    buildConf("celeborn.worker.storage.dir.number")
      .withAlternative("rss.worker.base.dir.number")
      .categories("worker")
      .doc("")
      .intConf
      .createWithDefault(16)

  val FLUSHER_HDD_THREAD_COUNT: ConfigEntry[Int] =
    buildConf("celeborn.flusher.hdd.thread.count")
      .withAlternative("rss.flusher.hdd.thread.count")
      .categories("worker")
      .doc("")
      .intConf
      .createWithDefault(1)

  val FLUSHER_SSD_THREAD_COUNT: ConfigEntry[Int] =
    buildConf("celeborn.flusher.ssd.thread.count")
      .withAlternative("rss.flusher.ssd.thread.count")
      .categories("worker")
      .doc("")
      .intConf
      .createWithDefault(8)

  val FLUSHER_HDFS_THREAD_COUNT: ConfigEntry[Int] =
    buildConf("celeborn.flusher.hdfs.thread.count")
      .withAlternative("rss.worker.hdfs.flusher.thread.count")
      .categories("worker")
      .doc("")
      .intConf
      .createWithDefault(4)

  val WORKER_FLUSH_BUFFER_SIZE: ConfigEntry[Long] =
    buildConf("celeborn.worker.flush.buffer.size")
      .withAlternative("rss.worker.flush.buffer.size")
      .categories("worker")
      .doc("Size of buffer used by a single flusher.")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("256k")

  def workerReplicateThreads(conf: RssConf): Int = conf.get(WORKER_REPLICATE_THREADS)

  def workerCommitThreads(conf: RssConf): Int = conf.get(WORKER_COMMIT_THREADS)

  def workerFlushBufferSize(conf: RssConf): Long = conf.get(WORKER_FLUSH_BUFFER_SIZE)

  def chunkSize(conf: RssConf): Long = {
    conf.getSizeAsBytes("rss.chunk.size", "8m")
  }

  def rpcMaxParallelism(conf: RssConf): Int = {
    conf.getInt("rss.rpc.max.parallelism", 1024)
  }

  def registerShuffleMaxRetry(conf: RssConf): Int = {
    conf.getInt("rss.register.shuffle.max.retry", 3)
  }

  def registerShuffleRetryWait(conf: RssConf): Long = {
    conf.getTimeAsSeconds("rss.register.shuffle.retry.wait", "3s")
  }

  def reserveSlotsMaxRetry(conf: RssConf): Int = {
    conf.getInt("rss.reserve.slots.max.retry", 3)
  }

  def reserveSlotsRetryWait(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.reserve.slots.retry.wait", "3s")
  }

  def flushTimeout(conf: RssConf): Long = {
    conf.getTimeAsSeconds("rss.flush.timeout", "120s")
  }

  def fileWriterTimeoutMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.filewriter.timeout", "120s")
  }

  val WORKER_STORAGE_EXPIRE_DURATION_MS: ConfigEntry[Long] =
    buildConf("celeborn.worker.storage.nonEmptyDir.expire.duration")
      .withAlternative("rss.expire.nonEmptyDir.duration")
      .categories("worker")
      .doc("")
      .timeConf(TimeUnit.MILLISECONDS)
      .createWithDefaultString("1d")

  def workerStorageExpireDurationMs(conf: RssConf): Long =
    conf.get(WORKER_STORAGE_EXPIRE_DURATION_MS)

  def workingDirName(conf: RssConf): String = conf.get(WORKER_WORKING_DIR_NAME)

  /**
   * @return workingDir, usable space, flusher thread count, disk type
   *         check more details at CONFIGURATION_GUIDE.md
   */
  def workerBaseDirs(conf: RssConf): Seq[(String, Long, Int, Type)] = {
    // I assume there is no disk is bigger than 1 PB in recent days.
    val defaultMaxCapacity = Utils.byteStringAsBytes("1PB")
    conf.get(WORKER_STORAGE_DIRS).map { storageDirs: Seq[String] =>
      storageDirs.map { str =>
        var maxCapacity = defaultMaxCapacity
        var diskType = HDD
        var flushThread = -1
        val (dir, attributes) = str.split(":").toList match {
          case _dir :: tail => (_dir, tail)
          case nil => throw new IllegalArgumentException(s"Illegal storage dir: $nil")
        }
        attributes.foreach {
          case capacityStr if capacityStr.toLowerCase.startsWith("capacity=") =>
            maxCapacity = Utils.byteStringAsBytes(capacityStr.split("=")(1))
          case diskTypeStr if diskTypeStr.toLowerCase.startsWith("disktype=") =>
            diskType = Type.valueOf(diskTypeStr.split("=")(1))
            if (diskType == Type.MEMORY) {
              throw new IOException(s"Invalid diskType: $diskType")
            }
          case threadCountStr if threadCountStr.toLowerCase.startsWith("flushthread=") =>
            flushThread = threadCountStr.split("=")(1).toInt
          case illegal =>
            throw new IllegalArgumentException(s"Illegal attribute: $illegal")
        }
        if (flushThread == -1) {
          flushThread = diskType match {
            case HDD => HDDFlusherThread(conf)
            case SSD => SSDFlusherThread(conf)
          }
        }
        (dir, maxCapacity, flushThread, diskType)
      }
    }.getOrElse {
      val prefix = RssConf.workerBaseDirPrefix(conf)
      val number = RssConf.workerBaseDirNumber(conf)
      (1 to number).map { i =>
        (s"$prefix$i", defaultMaxCapacity, HDDFlusherThread(conf), HDD)
      }
    }
  }

  def workerBaseDirPrefix(conf: RssConf): String = conf.get(WORKER_STORAGE_DIR_PREFIX)

  def workerBaseDirNumber(conf: RssConf): Int = conf.get(WORKER_STORAGE_DIR_NUMBER)

  def HDDFlusherThread(conf: RssConf): Int = conf.get(FLUSHER_HDD_THREAD_COUNT)

  def SSDFlusherThread(conf: RssConf): Int = conf.get(FLUSHER_SSD_THREAD_COUNT)

  val DISK_MINIMUM_RESERVE_SIZE: ConfigEntry[Long] =
    buildConf("celeborn.disk.minimum.reserve.size")
      .withAlternative("rss.disk.minimum.reserve.size")
      .categories("master", "worker")
      .doc("")
      .bytesConf(ByteUnit.BYTE)
      .createWithDefaultString("5G")

  def diskMinimumReserveSize(conf: RssConf): Long = conf.get(DISK_MINIMUM_RESERVE_SIZE)

  /**
   * @return This configuration is a guidance for load-aware slot allocation algorithm. This value
   *         is control how many disk groups will be created.
   */
  def diskGroups(conf: RssConf): Int = {
    conf.getInt("rss.disk.groups", 5)
  }

  def diskGroupGradient(conf: RssConf): Double = {
    conf.getDouble("rss.disk.group.gradient", 0.1)
  }

  def initialPartitionSize(conf: RssConf): Long = {
    Utils.byteStringAsBytes(conf.get("rss.initial.partition.size", "64m"))
  }

  def minimumPartitionSizeForEstimation(conf: RssConf): Long = {
    Utils.byteStringAsBytes(conf.get("rss.minimum.estimate.partition.size", "8m"))
  }

  def partitionSizeUpdaterInitialDelay(conf: RssConf): Long = {
    Utils.timeStringAsMs(conf.get("rss.partition.size.update.initial.delay", "5m"))
  }

  def partitionSizeUpdateInterval(conf: RssConf): Long = {
    Utils.timeStringAsMs(conf.get("rss.partition.size.update.interval", "10m"))
  }

  def stageEndTimeout(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.stage.end.timeout", "240s")
  }

  def limitInFlightTimeoutMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.limit.inflight.timeout", "240s")
  }

  def limitInFlightSleepDeltaMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.limit.inflight.sleep.delta", "50ms")
  }

  def pushServerPort(conf: RssConf): Int = {
    conf.getInt("rss.pushserver.port", 0)
  }

  def fetchServerPort(conf: RssConf): Int = {
    conf.getInt("rss.fetchserver.port", 0)
  }

  def replicateServerPort(conf: RssConf): Int = {
    conf.getInt("rss.replicateserver.port", 0)
  }

  def registerWorkerTimeoutMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.register.worker.timeout", "180s")
  }

  def masterPortMaxRetry(conf: RssConf): Int = {
    conf.getInt("rss.master.port.maxretry", 1)
  }

  def pushDataRetryThreadNum(conf: RssConf): Int = {
    conf.getInt(
      "rss.pushdata.retry.thread.num",
      Math.max(8, Runtime.getRuntime.availableProcessors()))
  }

  val METRICS_ENABLED: ConfigEntry[Boolean] =
    buildConf("celeborn.metrics.enabled")
      .withAlternative("rss.metrics.system.enabled")
      .categories("master", "worker")
      .doc("When true, enable metrics system.")
      .booleanConf
      .createWithDefault(true)

  val METRICS_TIMER_SLIDING_SIZE: ConfigEntry[Int] =
    buildConf("celeborn.metrics.timer.sliding.size")
      .withAlternative("rss.metrics.system.timer.sliding.size")
      .categories("master", "worker")
      .intConf
      .createWithDefault(4000)

  val METRICS_SAMPLE_RATE: ConfigEntry[Double] =
    buildConf("celeborn.metrics.sample.rate")
      .withAlternative("rss.metrics.system.sample.rate")
      .categories("master", "worker")
      .doubleConf
      .checkValue(v => v >= 0.0 && v <= 1.0, "should be in [0.0, 1.0]")
      .createWithDefault(1.0)

  val METRICS_SLIDING_WINDOW_SIZE: ConfigEntry[Int] =
    buildConf("celeborn.metrics.timer.sliding.window.size")
      .withAlternative("rss.metrics.system.sliding.window.size")
      .categories("master", "worker")
      .intConf
      .createWithDefault(4096)

  val MASTER_PROMETHEUS_HOST: ConfigEntry[String] =
    buildConf("celeborn.master.metrics.prometheus.host")
      .withAlternative("rss.master.prometheus.metric.host")
      .categories("master")
      .stringConf
      .createWithDefault("0.0.0.0")

  val MASTER_PROMETHEUS_PORT: ConfigEntry[Int] =
    buildConf("celeborn.master.metrics.prometheus.port")
      .withAlternative("rss.master.prometheus.metric.port")
      .categories("master")
      .intConf
      .checkValue(p => p >= 1024 && p < 65535, "invalid port")
      .createWithDefault(9098)

  val WORKER_PROMETHEUS_HOST: ConfigEntry[String] =
    buildConf("celeborn.worker.metrics.prometheus.host")
      .withAlternative("rss.worker.prometheus.metric.host")
      .categories("worker")
      .stringConf
      .createWithDefault("0.0.0.0")

  val WORKER_PROMETHEUS_PORT: ConfigEntry[Int] =
    buildConf("celeborn.worker.metrics.prometheus.port")
      .withAlternative("rss.worker.prometheus.metric.port")
      .categories("worker")
      .intConf
      .checkValue(p => p >= 1024 && p < 65535, "invalid port")
      .createWithDefault(9096)

  def metricsSystemEnable(conf: RssConf): Boolean = conf.get(METRICS_ENABLED)

  def metricsTimerSlidingSize(conf: RssConf): Int = conf.get(METRICS_TIMER_SLIDING_SIZE)

  def metricsSampleRate(conf: RssConf): Double = conf.get(METRICS_SAMPLE_RATE)

  def metricsSamplePerfCritical(conf: RssConf): Boolean = {
    conf.getBoolean("rss.metrics.system.sample.perf.critical", false)
  }

  def metricsSlidingWindowSize(conf: RssConf): Int = conf.get(METRICS_SLIDING_WINDOW_SIZE)

  def innerMetricsSize(conf: RssConf): Int = {
    conf.getInt("rss.inner.metrics.size", 4096)
  }

  def masterPrometheusMetricHost(conf: RssConf): String = conf.get(MASTER_PROMETHEUS_HOST)

  def masterPrometheusMetricPort(conf: RssConf): Int = conf.get(MASTER_PROMETHEUS_PORT)

  def workerPrometheusMetricHost(conf: RssConf): String = conf.get(WORKER_PROMETHEUS_HOST)

  def workerPrometheusMetricPort(conf: RssConf): Int = conf.get(WORKER_PROMETHEUS_PORT)

  def workerRPCPort(conf: RssConf): Int = {
    conf.getInt("rss.worker.rpc.port", 0)
  }

  def offerSlotsExtraSize(conf: RssConf): Int = {
    conf.getInt("rss.offer.slots.extra.size", 2)
  }

  def shuffleWriterMode(conf: RssConf): String = conf.get(SHUFFLE_WRITER_MODE)

  def sortPushThreshold(conf: RssConf): Long = {
    conf.getSizeAsBytes("rss.sort.push.data.threshold", "64m")
  }

  def driverMetaServicePort(conf: RssConf): Int = {
    val port = conf.getInt("rss.driver.metaService.port", 0)
    if (port != 0) {
      logWarning("The user specifies the port used by the LifecycleManager on the Driver, and its" +
        s" values is $port, which may cause port conflicts and startup failure.")
    }
    port
  }

  def closeIdleConnections(conf: RssConf): Boolean = {
    conf.getBoolean("rss.worker.closeIdleConnections", defaultValue = false)
  }

  def replicateFastFailDurationMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.replicate.fastfail.duration", "60s")
  }

  def maxPartitionNumSupported(conf: RssConf): Long = {
    conf.getInt("rss.max.partition.number", 500000)
  }

  def forceFallback(conf: RssConf): Boolean = {
    conf.getBoolean("rss.force.fallback", false)
  }

  def clusterCheckQuotaEnabled(conf: RssConf): Boolean = {
    conf.getBoolean("rss.cluster.checkQuota.enabled", defaultValue = true)
  }

  val WORKER_DEVICE_MONITOR_ENABLED: ConfigEntry[Boolean] =
    buildConf("celeborn.worker.deviceMonitor.enabled")
      .withAlternative("rss.device.monitor.enabled")
      .categories("worker")
      .doc("When true, worker will monitor device and report to master.")
      .booleanConf
      .createWithDefault(true)

  val WORKER_DEVICE_MONITOR_CHECKLIST: ConfigEntry[Seq[String]] =
    buildConf("celeborn.worker.deviceMonitor.checklist")
      .withAlternative("rss.device.monitor.checklist")
      .categories("worker")
      .doc("Select what the device needs to detect, available items are: " +
        "iohang, readwrite and diskusage.")
      .stringConf
      .transform(_.toLowerCase)
      .toSequence
      .createWithDefaultString("readwrite,diskusage")

  def deviceMonitorEnabled(conf: RssConf): Boolean = conf.get(WORKER_DEVICE_MONITOR_ENABLED)

  def deviceMonitorCheckList(conf: RssConf): Seq[String] = conf.get(WORKER_DEVICE_MONITOR_CHECKLIST)

  def diskCheckIntervalMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.disk.check.interval", "60s")
  }

  def slowFlushIntervalMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.slow.flush.interval", "10s")
  }

  def sysBlockDir(conf: RssConf): String = {
    conf.get("rss.sys.block.dir", "/sys/block")
  }

  val WORKER_FILE_WRITER_CREATE_RETRY_COUNT: ConfigEntry[Int] =
    buildConf("celeborn.fileWriter.create.retry.count")
      .withAlternative("rss.create.file.writer.retry.count")
      .categories("worker")
      .doc("")
      .intConf
      .createWithDefault(3)

  def createFileWriterRetryCount(conf: RssConf): Int =
    conf.get(WORKER_FILE_WRITER_CREATE_RETRY_COUNT)

  val WORKER_STATUS_CHECK_TIMEOUT: ConfigEntry[Long] =
    buildConf("celeborn.worker.status.check.timeout")
      .withAlternative("rss.worker.status.check.timeout")
      .categories("worker")
      .doc("")
      .timeConf(TimeUnit.SECONDS)
      .createWithDefaultString("30s")

  def workerStatusCheckTimeoutSeconds(conf: RssConf): Long = conf.get(WORKER_STATUS_CHECK_TIMEOUT)

  val WORKER_CLEAN_RESIDUAL_FILE_RETRY_TIME: ConfigEntry[Int] =
    buildConf("celeborn.worker.clean.residualFile.retryTime")
      .withAlternative("rss.worker.checkFileCleanRetryTimes")
      .categories("worker")
      .doc("")
      .intConf
      .createWithDefault(3)

  val WORKER_CLEAN_RESIDUAL_FILE_INTERVAL_MS: ConfigEntry[Long] =
    buildConf("celeborn.worker.clean.residualFile.interval")
      .withAlternative("rss.worker.checkFileCleanTimeoutMs")
      .categories("worker")
      .doc("")
      .timeConf(TimeUnit.MILLISECONDS)
      .createWithDefaultString("1000ms")

  def cleanResidualFileRetryTime(conf: RssConf): Int =
    conf.get(WORKER_CLEAN_RESIDUAL_FILE_RETRY_TIME)

  def cleanResidualFileIntervalMs(conf: RssConf): Long =
    conf.get(WORKER_CLEAN_RESIDUAL_FILE_INTERVAL_MS)

  def haClientMaxTries(conf: RssConf): Int = {
    conf.getInt("rss.ha.client.maxTries", 15)
  }

  def clusterSlotsUsageLimitPercent(conf: RssConf): Double = {
    conf.getDouble("rss.slots.usage.overload.percent", 0.95)
  }

  def identityProviderClass(conf: RssConf): String = {
    conf.get("rss.identity.provider", classOf[DefaultIdentityProvider].getName)
  }

  def quotaManagerClass(conf: RssConf): String = {
    conf.get("rss.quota.manager", classOf[DefaultQuotaManager].getName)
  }

  def quotaConfigurationPath(conf: RssConf): Option[String] = {
    conf.getOption("rss.quota.configuration.path")
  }

  def partitionSplitThreshold(conf: RssConf): Long = {
    conf.getSizeAsBytes("rss.partition.split.threshold", "256m")
  }

  def partitionSplitMinimumSize(conf: RssConf): Long = {
    conf.getSizeAsBytes("rss.partition.split.minimum.size", "1m")
  }

  def batchHandleChangePartitionEnabled(conf: RssConf): Boolean = {
    conf.getBoolean("rss.change.partition.batch.enabled", false)
  }

  def batchHandleChangePartitionNumThreads(conf: RssConf): Int = {
    conf.getInt("rss.change.partition.numThreads", 8)
  }

  def handleChangePartitionRequestBatchInterval(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.change.partition.batchInterval", "100ms")
  }

  def partitionSplitMode(conf: RssConf): PartitionSplitMode = {
    val modeStr = conf.get("rss.partition.split.mode", "soft")
    modeStr match {
      case "soft" => PartitionSplitMode.SOFT
      case "hard" => PartitionSplitMode.HARD
      case _ =>
        logWarning(s"Invalid split mode ${modeStr}, use soft mode by default")
        PartitionSplitMode.SOFT
    }
  }

  def partitionType(conf: RssConf): PartitionType = {
    val typeStr = conf.get("rss.partition.type", "reduce")
    typeStr match {
      case "reduce" => PartitionType.REDUCE_PARTITION
      case "map" => PartitionType.MAP_PARTITION
      case "mapgroup" => PartitionType.MAPGROUP_REDUCE_PARTITION
      case _ =>
        logWarning(s"Invalid split mode $typeStr, use ReducePartition by default")
        PartitionType.REDUCE_PARTITION
    }
  }

  def clientSplitPoolSize(conf: RssConf): Int = {
    conf.getInt("rss.client.split.pool.size", 8)
  }

  // Support 2 type codecs: lz4 and zstd
  def compressionCodec(conf: RssConf): String = {
    conf.get("rss.client.compression.codec", "lz4").toLowerCase
  }

  def zstdCompressLevel(conf: RssConf): Int = {
    val level = conf.getInt("rss.client.compression.zstd.level", 1)
    val zstdMinLevel = -5
    val zstdMaxLevel = 22
    Math.min(Math.max(Math.max(level, zstdMinLevel), Math.min(level, zstdMaxLevel)), zstdMaxLevel)
  }

  def partitionSortTimeout(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.partition.sort.timeout", "220s")
  }

  def partitionSortMaxMemoryRatio(conf: RssConf): Double = {
    conf.getDouble("rss.partition.sort.memory.max.ratio", 0.1)
  }

  def workerPausePushDataRatio(conf: RssConf): Double = {
    conf.getDouble("rss.pause.pushdata.memory.ratio", 0.85)
  }

  def workerPauseRepcaliteRatio(conf: RssConf): Double = {
    conf.getDouble("rss.pause.replicate.memory.ratio", 0.95)
  }

  def workerResumeRatio(conf: RssConf): Double = {
    conf.getDouble("rss.resume.memory.ratio", 0.5)
  }

  def initialReserveSingleSortMemory(conf: RssConf): Long = {
    conf.getSizeAsBytes("rss.worker.initialReserveSingleSortMemory", "1mb")
  }

  def workerDirectMemoryPressureCheckIntervalMs(conf: RssConf): Int = {
    conf.getInt("rss.worker.memory.check.interval", 10)
  }

  def workerDirectMemoryReportIntervalSecond(conf: RssConf): Int = {
    Utils.timeStringAsSeconds(conf.get("rss.worker.memory.report.interval", "10s")).toInt
  }

  def defaultStorageType(conf: RssConf): StorageInfo.Type = {
    val default = StorageInfo.Type.MEMORY
    val hintStr = conf.get("rss.storage.type", "memory").toUpperCase
    if (StorageInfo.Type.values().mkString.toUpperCase.contains(hintStr)) {
      logWarning(s"storage hint is invalid ${hintStr}")
      StorageInfo.Type.valueOf(hintStr)
    } else {
      default
    }
  }

  def checkSlotsFinishedInterval(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.worker.checkSlots.interval", "1s")
  }

  def checkSlotsFinishedTimeoutMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.worker.checkSlots.timeout", "480s")
  }

  val WORKER_GRACEFUL_SHUTDOWN_ENABLED: ConfigEntry[Boolean] =
    buildConf("celeborn.worker.shutdown.graceful")
      .withAlternative("rss.worker.graceful.shutdown")
      .categories("worker")
      .doc("")
      .booleanConf
      .createWithDefault(false)

  def workerGracefulShutdown(conf: RssConf): Boolean = conf.get(WORKER_GRACEFUL_SHUTDOWN_ENABLED)

  def shutdownTimeoutMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.worker.shutdown.timeout", "600s")
  }

  val WORKER_RECOVER_PATH: ConfigEntry[String] =
    buildConf("celeborn.worker.recover.path")
      .withAlternative("rss.worker.recoverPath")
      .categories("worker")
      .doc("")
      .stringConf
      .createWithDefault(s"${System.getProperty("java.io.tmpdir")}/recover")

  def workerRecoverPath(conf: RssConf): String = conf.get(WORKER_RECOVER_PATH)

  def partitionSorterCloseAwaitTimeMs(conf: RssConf): Long = {
    conf.getTimeAsMs("rss.worker.partitionSorterCloseAwaitTime", "120s")
  }

  def offerSlotsAlgorithm(conf: RssConf): String = {
    var algorithm = conf.get("rss.offer.slots.algorithm", "roundrobin")
    if (algorithm != "loadaware" && algorithm != "roundrobin") {
      logWarning(s"Config rss.offer.slots.algorithm is wrong ${algorithm}." +
        s" Use default roundrobin")
      algorithm = "roundrobin"
    }
    algorithm
  }

  val FLUSHER_AVG_TIME_WINDOW_SIZE: ConfigEntry[Int] =
    buildConf("celeborn.flusher.avg.time.window.size")
      .withAlternative("rss.flusher.avg.time.window")
      .categories("worker")
      .doc("")
      .intConf
      .createWithDefault(20)

  val FLUSHER_AVG_TIME_MINIMUM_COUNT: ConfigEntry[Int] =
    buildConf("celeborn.flusher.avg.time.minimum.count")
      .withAlternative("rss.flusher.avg.time.minimum.count")
      .categories("worker")
      .doc("")
      .intConf
      .createWithDefault(1000)

  def flushAvgTimeWindow(conf: RssConf): Int = conf.get(FLUSHER_AVG_TIME_WINDOW_SIZE)

  def flushAvgTimeMinimumCount(conf: RssConf): Int = conf.get(FLUSHER_AVG_TIME_MINIMUM_COUNT)

  val WORKER_HDFS_DIR: ConfigEntry[String] =
    buildConf("celeborn.worker.hdfs.dir")
      .withAlternative("rss.worker.hdfs.dir")
      .categories("worker")
      .doc("")
      .stringConf
      .createWithDefault("")

  def hdfsDir(conf: RssConf): String = {
    val hdfsDir = conf.get(WORKER_HDFS_DIR)
    if (hdfsDir.nonEmpty && !Utils.isHdfsPath(hdfsDir)) {
      log.error(s"rss.worker.hdfs.dir configuration is wrong $hdfsDir. Disable hdfs support.")
      ""
    } else {
      hdfsDir
    }
  }

  def hdfsFlusherThreadCount(conf: RssConf): Int = conf.get(FLUSHER_HDFS_THREAD_COUNT)

  def rangeReadFilterEnabled(conf: RssConf): Boolean = {
    conf.getBoolean("rss.range.read.filter.enabled", false)
  }

  def columnarShuffleEnabled(conf: RssConf): Boolean = {
    conf.getBoolean("rss.columnar.shuffle.enabled", defaultValue = false)
  }

  def columnarShuffleCompress(conf: RssConf): Boolean = {
    conf.getBoolean("rss.columnar.shuffle.encoding.enabled", defaultValue = false)
  }

  def columnarShuffleBatchSize(conf: RssConf): Int = {
    conf.getInt("rss.columnar.shuffle.batch.size", 10000)
  }

  def columnarShuffleOffHeapColumnVectorEnabled(conf: RssConf): Boolean = {
    conf.getBoolean("rss.columnar.shuffle.offheap.vector.enabled", false)
  }

  def columnarShuffleMaxDictFactor(conf: RssConf): Double = {
    conf.getDouble("rss.columnar.shuffle.max.dict.factor", 0.3)
  }
}
