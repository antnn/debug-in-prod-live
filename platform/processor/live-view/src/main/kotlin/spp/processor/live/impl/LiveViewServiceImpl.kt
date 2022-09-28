/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package spp.processor.live.impl

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.apache.skywalking.oap.log.analyzer.module.LogAnalyzerModule
import org.apache.skywalking.oap.log.analyzer.provider.log.ILogAnalyzerService
import org.apache.skywalking.oap.log.analyzer.provider.log.LogAnalyzerServiceImpl
import org.apache.skywalking.oap.server.analyzer.module.AnalyzerModule
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.ISegmentParserService
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserListenerManager
import org.apache.skywalking.oap.server.analyzer.provider.trace.parser.SegmentParserServiceImpl
import org.slf4j.LoggerFactory
import spp.platform.common.DeveloperAuth
import spp.platform.common.FeedbackProcessor
import spp.processor.ViewProcessor
import spp.processor.live.impl.view.LiveLogsView
import spp.processor.live.impl.view.LiveMeterView
import spp.processor.live.impl.view.LiveTracesView
import spp.processor.live.impl.view.util.EntitySubscribersCache
import spp.processor.live.impl.view.util.MetricTypeSubscriptionCache
import spp.processor.live.impl.view.util.ViewSubscriber
import spp.protocol.SourceServices.Subscribe.toLiveViewSubscriberAddress
import spp.protocol.platform.PlatformAddress.MARKER_DISCONNECTED
import spp.protocol.service.LiveViewService
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewEvent
import java.util.*

class LiveViewServiceImpl : CoroutineVerticle(), LiveViewService {

    companion object {
        private val log = LoggerFactory.getLogger(LiveViewServiceImpl::class.java)
    }

    //todo: use ExpiringSharedData
    private val subscriptionCache = MetricTypeSubscriptionCache()
    val meterView = LiveMeterView(subscriptionCache)
    val tracesView = LiveTracesView(subscriptionCache)
    val logsView = LiveLogsView(subscriptionCache)

    override suspend fun start() {
        log.info("Starting LiveViewServiceImpl")

        //live traces view
        val segmentParserService = FeedbackProcessor.module!!.find(AnalyzerModule.NAME)
            .provider().getService(ISegmentParserService::class.java) as SegmentParserServiceImpl
        val listenerManagerField = segmentParserService.javaClass.getDeclaredField("listenerManager")
        listenerManagerField.trySetAccessible()
        val listenerManager = listenerManagerField.get(segmentParserService) as SegmentParserListenerManager
        listenerManager.add(ViewProcessor.liveViewService.tracesView)

        //live logs view
        val logParserService = FeedbackProcessor.module!!.find(LogAnalyzerModule.NAME)
            .provider().getService(ILogAnalyzerService::class.java) as LogAnalyzerServiceImpl
        logParserService.addListenerFactory(ViewProcessor.liveViewService.logsView)

        vertx.eventBus().consumer<JsonObject>(MARKER_DISCONNECTED) {
            val devAuth = DeveloperAuth.from(it.body())
            clearLiveViews(devAuth.selfId).onComplete {
                if (it.succeeded()) {
                    log.info("Cleared live views for disconnected marker: {}", devAuth.selfId)
                } else {
                    log.error("Failed to clear live views on marker disconnection", it.cause())
                }
            }
        }
    }

    override fun addLiveView(subscription: LiveView): Future<LiveView> {
        val promise = Promise.promise<LiveView>()
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        val address = "view." + UUID.randomUUID().toString()
        val sub = subscription.copy(subscriptionId = address)
        log.info("Adding live view: {}", sub)

        val consumer = vertx.eventBus().consumer<JsonObject>(address)
        consumer.handler {
            val event = it.body()
            val viewEvent = if (event.getBoolean("multiMetrics")) {
                val events = event.getJsonArray("metrics")
                val firstEvent = events.getJsonObject(0)
                LiveViewEvent(
                    sub.subscriptionId!!,
                    sub.entityIds.first(),
                    sub.artifactQualifiedName,
                    firstEvent.getString("timeBucket"),
                    sub.liveViewConfig,
                    events.toString()
                )
            } else {
                LiveViewEvent(
                    sub.subscriptionId!!,
                    sub.entityIds.first(),
                    sub.artifactQualifiedName,
                    event.getString("timeBucket"),
                    sub.liveViewConfig,
                    event.toString()
                )
            }

            vertx.eventBus().publish(
                toLiveViewSubscriberAddress(devAuth.selfId),
                JsonObject.mapFrom(viewEvent)
            )
        }.completionHandler {
            if (it.succeeded()) {
                val subscriber = ViewSubscriber(
                    sub,
                    devAuth.selfId,
                    System.currentTimeMillis(),
                    mutableMapOf(),
                    consumer
                )

                sub.liveViewConfig.viewMetrics.forEach {
                    subscriptionCache.computeIfAbsent(it) { EntitySubscribersCache() }
                    sub.entityIds.forEach { entityId ->
                        subscriptionCache[it]!!.computeIfAbsent(entityId) { mutableSetOf() }
                        (subscriptionCache[it]!![entityId]!! as MutableSet).add(subscriber)
                    }
                }

                promise.complete(sub)
            } else {
                promise.fail(it.cause())
            }
        }
        return promise.future()
    }

    override fun removeLiveView(id: String): Future<LiveView> {
        log.debug("Removing live view: {}", id)
        val promise = Promise.promise<LiveView>()
        var unsubbedUser: ViewSubscriber? = null
        subscriptionCache.flatMap { it.value.values }.forEach { subList ->
            val subscription = subList.firstOrNull { it.subscription.subscriptionId == id }
            if (subscription != null) {
                (subList as MutableSet).remove(subscription)
                unsubbedUser = subscription
            }
        }

        if (unsubbedUser != null) {
            unsubbedUser!!.consumer.unregister {
                if (it.succeeded()) {
                    log.info("Removed live view: {}", id)
                    promise.complete(
                        LiveView(
                            unsubbedUser!!.subscription.subscriptionId,
                            unsubbedUser!!.subscription.entityIds,
                            unsubbedUser!!.subscription.artifactQualifiedName,
                            unsubbedUser!!.subscription.artifactLocation,
                            unsubbedUser!!.subscription.liveViewConfig
                        )
                    )
                } else {
                    promise.fail(it.cause())
                }
            }
        } else {
            promise.fail(IllegalStateException("Invalid subscription id"))
        }
        return promise.future()
    }

    override fun updateLiveView(
        id: String,
        subscription: LiveView
    ): Future<LiveView> {
        log.debug("Updating live view: {}", id)
        val promise = Promise.promise<LiveView>()

        var viewSubscriber: ViewSubscriber? = null
        subscriptionCache.forEach {
            it.value.forEach {
                it.value.forEach {
                    if (it.subscription.subscriptionId == id) {
                        viewSubscriber = it
                    }
                }
            }
        }

        if (viewSubscriber != null) {
            val removedEntityIds = viewSubscriber!!.subscription.entityIds - subscription.entityIds
            val addedEntityIds = subscription.entityIds - viewSubscriber!!.subscription.entityIds
            viewSubscriber!!.subscription.entityIds.removeAll(removedEntityIds)
            viewSubscriber!!.subscription.entityIds.addAll(addedEntityIds)

            subscription.liveViewConfig.viewMetrics.forEach {
                subscriptionCache.computeIfAbsent(it) { EntitySubscribersCache() }

                removedEntityIds.forEach { entityId ->
                    val entityIdMetrics = subscriptionCache[it]!![entityId]!! as MutableSet
                    entityIdMetrics.removeIf {
                        it.subscription.subscriptionId == id
                    }
                    if (entityIdMetrics.isEmpty()) {
                        subscriptionCache[it]!!.remove(entityId)
                    }
                }
                addedEntityIds.forEach { entityId ->
                    subscriptionCache[it]!!.computeIfAbsent(entityId) { mutableSetOf() }
                    (subscriptionCache[it]!![entityId]!! as MutableSet).add(viewSubscriber!!)
                }
            }

            promise.complete(subscription)
        } else {
            promise.fail(IllegalStateException("Invalid subscription id"))
        }

        return promise.future()
    }

    override fun getLiveView(id: String): Future<LiveView> {
        log.debug("Getting live view: {}", id)
        val promise = Promise.promise<LiveView>()
        var subbedUser: ViewSubscriber? = null
        subscriptionCache.flatMap { it.value.values }.forEach { subList ->
            val subscription = subList.firstOrNull { it.subscription.subscriptionId == id }
            if (subscription != null) {
                subbedUser = subscription
            }
        }

        if (subbedUser != null) {
            promise.complete(
                LiveView(
                    subbedUser!!.subscription.subscriptionId,
                    subbedUser!!.subscription.entityIds,
                    subbedUser!!.subscription.artifactQualifiedName,
                    subbedUser!!.subscription.artifactLocation,
                    subbedUser!!.subscription.liveViewConfig
                )
            )
        } else {
            promise.fail(IllegalStateException("Invalid subscription id"))
        }
        return promise.future()
    }

    override fun getLiveViews(): Future<List<LiveView>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        val viewSubscriptions = mutableSetOf<LiveView>()
        subscriptionCache.forEach {
            it.value.forEach {
                it.value.forEach {
                    if (it.subscriberId == devAuth.selfId) {
                        viewSubscriptions.add(it.subscription)
                    }
                }
            }
        }
        return Future.succeededFuture(viewSubscriptions.toList())
    }

    override fun clearLiveViews(): Future<List<LiveView>> {
        val devAuth = Vertx.currentContext().getLocal<DeveloperAuth>("developer")
        return clearLiveViews(devAuth.selfId)
    }

    private fun clearLiveViews(subscriberId: String): Future<List<LiveView>> {
        val promise = Promise.promise<List<LiveView>>()
        val removedSubs = mutableSetOf<ViewSubscriber>()
        subscriptionCache.entries.removeIf {
            it.value.entries.removeIf {
                (it.value as MutableSet).removeIf {
                    if (it.subscriberId == subscriberId) {
                        removedSubs.add(it)
                        true
                    } else false
                }
                it.value.isEmpty()
            }
            it.value.isEmpty()
        }
        if (removedSubs.isNotEmpty()) {
            removedSubs.forEach {
                it.consumer.unregister()
            }
            promise.complete(removedSubs.map { it.subscription })
        } else {
            promise.complete(emptyList())
        }
        return promise.future()
    }

    override fun getLiveViewStats(): Future<JsonObject> {
        val subStats = JsonObject()
        subscriptionCache.forEach { type ->
            subStats.put(type.key, JsonObject())
            type.value.forEach { key, value ->
                subStats.getJsonObject(type.key).put(key, value.size)
            }
        }
        return Future.succeededFuture(subStats)
    }
}
