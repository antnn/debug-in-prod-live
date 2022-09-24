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
package integration

import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewSubscription

class LiveViewServiceImplTest : PlatformIntegrationTest() {

    @BeforeEach
    fun reset(): Unit = runBlocking {
        viewService.clearLiveViewSubscriptions().await()
    }

    @Test
    fun `test addLiveViewSubscription`(): Unit = runBlocking {
        val subscription = LiveViewSubscription(
            entityIds = mutableSetOf("test-id"),
            liveViewConfig = LiveViewConfig(
                "test",
                listOf("test-id")
            )
        )
        val subscriptionId = viewService.addLiveViewSubscription(subscription).await().subscriptionId!!

        val subscriptions = viewService.getLiveViewSubscriptions().await()
        assertEquals(1, subscriptions.size)
        assertEquals(subscriptionId, subscriptions[0].subscriptionId)
        assertEquals(subscription.copy(subscriptionId = subscriptionId), subscriptions[0])
    }

    @Test
    fun `test updateLiveViewSubscription`(): Unit = runBlocking {
        val subscription = LiveViewSubscription(
            entityIds = mutableSetOf("test-id"),
            liveViewConfig = LiveViewConfig(
                "test",
                listOf("test-id")
            )
        )
        val subscriptionId = viewService.addLiveViewSubscription(subscription).await().subscriptionId!!

        val updatedSubscription = subscription.copy(entityIds = mutableSetOf("test-id-2"))
        viewService.updateLiveViewSubscription(subscriptionId, updatedSubscription).await()

        val subscriptions = viewService.getLiveViewSubscriptions().await()
        assertEquals(1, subscriptions.size)
        assertEquals(subscriptionId, subscriptions[0].subscriptionId)
        assertEquals(
            updatedSubscription.copy(
                subscriptionId = subscriptionId,
                entityIds = mutableSetOf("test-id", "test-id-2")
            ), subscriptions[0]
        )
    }

    @Test
    fun `test removeLiveViewSubscription`(): Unit = runBlocking {
        val subscription = LiveViewSubscription(
            entityIds = mutableSetOf("test-id"),
            liveViewConfig = LiveViewConfig(
                "test",
                listOf("test-id")
            )
        )
        val subscriptionId = viewService.addLiveViewSubscription(subscription).await().subscriptionId!!

        val removedSubscription = viewService.removeLiveViewSubscription(subscriptionId).await()
        assertEquals(subscriptionId, removedSubscription.subscriptionId)
        assertEquals(subscription.copy(subscriptionId = subscriptionId), removedSubscription)

        val subscriptions = viewService.getLiveViewSubscriptions().await()
        assertEquals(0, subscriptions.size)
    }

    @Test
    fun `test getLiveViewSubscription`(): Unit = runBlocking {
        val subscription = LiveViewSubscription(
            entityIds = mutableSetOf("test-id"),
            liveViewConfig = LiveViewConfig(
                "test",
                listOf("test-id")
            )
        )
        val subscriptionId = viewService.addLiveViewSubscription(subscription).await().subscriptionId!!

        val retrievedSubscription = viewService.getLiveViewSubscription(subscriptionId).await()
        assertEquals(subscriptionId, retrievedSubscription.subscriptionId)
        assertEquals(subscription.copy(subscriptionId = subscriptionId), retrievedSubscription)
    }

    @Test
    fun `test getLiveViewSubscriptions`(): Unit = runBlocking {
        val subscription1 = LiveViewSubscription(
            entityIds = mutableSetOf("test-id-1"),
            liveViewConfig = LiveViewConfig(
                "test",
                listOf("test-id-1")
            )
        )
        val subscriptionId1 = viewService.addLiveViewSubscription(subscription1).await().subscriptionId!!

        val subscription2 = LiveViewSubscription(
            entityIds = mutableSetOf("test-id-2"),
            liveViewConfig = LiveViewConfig(
                "test",
                listOf("test-id-2")
            )
        )
        val subscriptionId2 = viewService.addLiveViewSubscription(subscription2).await().subscriptionId!!

        val subscriptions = viewService.getLiveViewSubscriptions().await()
        assertEquals(2, subscriptions.size)
        assertEquals(subscriptionId1, subscriptions[1].subscriptionId)
        assertEquals(subscription1.copy(subscriptionId = subscriptionId1), subscriptions[1])
        assertEquals(subscriptionId2, subscriptions[0].subscriptionId)
        assertEquals(subscription2.copy(subscriptionId = subscriptionId2), subscriptions[0])
    }

    @Test
    fun `test clearLiveViewSubscriptions`(): Unit = runBlocking {
        val subscription1 = LiveViewSubscription(
            entityIds = mutableSetOf("test-id-1"),
            liveViewConfig = LiveViewConfig(
                "test",
                listOf("test-id-1")
            )
        )
        viewService.addLiveViewSubscription(subscription1).await()

        val subscription2 = LiveViewSubscription(
            entityIds = mutableSetOf("test-id-2"),
            liveViewConfig = LiveViewConfig(
                "test",
                listOf("test-id-2")
            )
        )
        viewService.addLiveViewSubscription(subscription2).await()

        val clearedSubscriptions = viewService.clearLiveViewSubscriptions().await()
        assertEquals(2, clearedSubscriptions.size)
        assertEquals(
            subscription1.copy(subscriptionId = clearedSubscriptions[1].subscriptionId),
            clearedSubscriptions[1]
        )
        assertEquals(
            subscription2.copy(subscriptionId = clearedSubscriptions[0].subscriptionId),
            clearedSubscriptions[0]
        )

        val subscriptions = viewService.getLiveViewSubscriptions().await()
        assertEquals(0, subscriptions.size)
    }
}