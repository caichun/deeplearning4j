/*******************************************************************************
 * Copyright (c) 2015-2019 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.nd4j.jita.allocator;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.junit.Ignore;
import org.junit.Test;
import org.nd4j.jita.allocator.impl.AtomicAllocator;
import org.nd4j.jita.allocator.impl.MemoryTracker;

import lombok.val;

import org.nd4j.jita.memory.impl.CudaFullCachingProvider;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.memory.conf.WorkspaceConfiguration;
import org.nd4j.linalg.api.memory.enums.MirroringPolicy;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.api.memory.enums.AllocationPolicy;
import org.nd4j.jita.memory.impl.CudaDirectProvider;
import org.nd4j.jita.memory.impl.CudaCachingZeroProvider;
import org.nd4j.jita.allocator.utils.AllocationUtils;
import org.nd4j.jita.allocator.enums.AllocationStatus;
import org.nd4j.jita.allocator.impl.AllocationPoint;
import org.nd4j.jita.allocator.enums.AllocationStatus;
import org.nd4j.jita.allocator.impl.AllocationShape;

import static org.junit.Assert.*;

@Ignore
@Slf4j
public class AllocatorTest {
    private static final long SAFETY_OFFSET = 1024L;	

    @Test
    public void testCounters() {
        int deviceId = 0;
        MemoryTracker tracker = new MemoryTracker();

        assertTrue(0 == tracker.getAllocatedAmount(deviceId));
        assertTrue(0 == tracker.getCachedAmount(deviceId));
        //assertTrue(0 == tracker.getTotalMemory(deviceId));

        tracker.incrementAllocatedAmount(deviceId, 10);
        assertTrue(10 == tracker.getAllocatedAmount(deviceId));

        tracker.incrementCachedAmount(deviceId, 5);
        assertTrue(5 == tracker.getCachedAmount(deviceId));

        tracker.decrementAllocatedAmount(deviceId, 5);
        assertTrue(5 == tracker.getAllocatedAmount(deviceId));

        tracker.decrementCachedAmount(deviceId, 5);
        assertTrue(0 == tracker.getCachedAmount(deviceId));

        //assertTrue(0 == tracker.getTotalMemory(deviceId));

        for (int e = 0; e < Nd4j.getAffinityManager().getNumberOfDevices(); e++) {
            val ttl = tracker.getTotalMemory(e);
            log.info("Device_{} {} bytes", e, ttl);
            assertNotEquals(0, ttl);
        }
    }

    @Test
    public void testWorkspaceInitSize() {

        long initSize = 1024;
	    MemoryTracker tracker = MemoryTracker.getInstance();

        WorkspaceConfiguration workspaceConfig = WorkspaceConfiguration.builder()
                .policyAllocation(AllocationPolicy.STRICT)
                .initialSize(initSize)
                .build();

	    try (val ws = Nd4j.getWorkspaceManager().getAndActivateWorkspace(workspaceConfig, "test121")) {
        	assertEquals(initSize + SAFETY_OFFSET, tracker.getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        }

	    val ws = Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread("test121");
	    ws.destroyWorkspace();

	    assertEquals(0, tracker.getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
    }


    @Test
    public void testWorkspaceSpilledSize() {

        long initSize = 0;
        MemoryTracker tracker = MemoryTracker.getInstance();

        WorkspaceConfiguration workspaceConfig = WorkspaceConfiguration.builder()
                .policyAllocation(AllocationPolicy.STRICT)
                .initialSize(initSize)
                .build();

        try (val ws = Nd4j.getWorkspaceManager().getAndActivateWorkspace(workspaceConfig, "test99323")) {
            assertEquals(0L, tracker.getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));

            val array = Nd4j.createFromArray(1.f, 2.f, 3.f, 4.f);

            assertEquals(array.length() * array.data().getElementSize(), tracker.getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        }

        val ws = Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread("test99323");
        ws.destroyWorkspace();

        assertEquals(0, tracker.getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
    }

    @Test
    public void testWorkspaceSpilledSizeHost() {

        long initSize = 0;
        MemoryTracker tracker = MemoryTracker.getInstance();

        WorkspaceConfiguration workspaceConfig = WorkspaceConfiguration.builder()
                .policyAllocation(AllocationPolicy.STRICT)
                .policyMirroring(MirroringPolicy.HOST_ONLY)
                .initialSize(initSize)
                .build();

        try (val ws = Nd4j.getWorkspaceManager().getAndActivateWorkspace(workspaceConfig, "test99323222")) {
            assertEquals(0L, tracker.getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));

            val array = Nd4j.createFromArray(1.f, 2.f, 3.f, 4.f);

            assertEquals(0, tracker.getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        }

        val ws = Nd4j.getWorkspaceManager().getWorkspaceForCurrentThread("test99323222");
        ws.destroyWorkspace();

        assertEquals(0, tracker.getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
    }


    @Ignore
    @Test
    public void testWorkspaceAlloc() {

        long initSize = 0;
        long allocSize = 48;

        val workspaceConfig = WorkspaceConfiguration.builder()
                .policyAllocation(AllocationPolicy.STRICT)
                .initialSize(initSize)
                .policyMirroring(MirroringPolicy.HOST_ONLY) // Commenting this out makes it so that assert is not triggered (for at least 40 secs or so...)
                .build();

	    try (val ws = Nd4j.getWorkspaceManager().getAndActivateWorkspace(workspaceConfig, "test")) {
        	final INDArray zeros = Nd4j.zeros(allocSize, 'c');
		System.out.println("Alloc1:" + MemoryTracker.getInstance().getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        	assertTrue(allocSize ==
                    MemoryTracker.getInstance().getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
	}
        assertTrue(allocSize == 
                MemoryTracker.getInstance().getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        /*Nd4j.getWorkspaceManager().destroyWorkspace(ws);
        assertTrue(0L ==
                MemoryTracker.getInstance().getWorkspaceAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));*/
    }

    @Test
    public void testDirectProvider() {
        INDArray input = Nd4j.zeros(1024);
        CudaDirectProvider provider = new CudaDirectProvider();
        AllocationShape shape = AllocationUtils.buildAllocationShape(input);
        AllocationPoint point = new AllocationPoint();
        point.setShape(shape);

        val allocBefore = MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());
        val cachedBefore = MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());

	    val pointers = provider.malloc(shape, point, AllocationStatus.DEVICE);
	    point.setPointers(pointers);

        System.out.println(MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        System.out.println(MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));

        val allocMiddle = MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());
        val cachedMiddle = MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());

	    provider.free(point);

        System.out.println(MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        System.out.println(MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));

        val allocAfter = MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());
        val cachedAfter = MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());

        assertTrue(allocBefore < allocMiddle);
        assertEquals(allocBefore, allocAfter);

        assertEquals(cachedBefore, cachedMiddle);
        assertEquals(cachedBefore, cachedAfter);
    }

    @Test
    public void testZeroCachingProvider() {
        INDArray input = Nd4j.zeros(1024);
        CudaCachingZeroProvider provider = new CudaCachingZeroProvider();
        AllocationShape shape = AllocationUtils.buildAllocationShape(input);
        AllocationPoint point = new AllocationPoint();
        point.setShape(shape);

        val allocBefore = MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());
        val cachedBefore = MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());

        val pointers = provider.malloc(shape, point, AllocationStatus.DEVICE);
        point.setPointers(pointers);

        System.out.println(MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        System.out.println(MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));

        val allocMiddle = MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());
        val cachedMiddle = MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());

        provider.free(point);

        System.out.println(MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        System.out.println(MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));

        val allocAfter = MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());
        val cachedAfter = MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());

        assertTrue(allocBefore < allocMiddle);
        assertEquals(allocBefore, allocAfter);

        assertEquals(cachedBefore, cachedMiddle);
        assertEquals(cachedBefore, cachedAfter);
    }

    @Test
    public void testFullCachingProvider() {
        INDArray input = Nd4j.zeros(1024);
        val provider = new CudaFullCachingProvider();
        AllocationShape shape = AllocationUtils.buildAllocationShape(input);
        AllocationPoint point = new AllocationPoint();
        point.setShape(shape);

        val allocBefore = MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());
        val cachedBefore = MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());

        val pointers = provider.malloc(shape, point, AllocationStatus.DEVICE);
        point.setPointers(pointers);

        System.out.println(MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        System.out.println(MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));

        val allocMiddle = MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());
        val cachedMiddle = MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());

        provider.free(point);

        System.out.println(MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        System.out.println(MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));

        val allocAfter = MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());
        val cachedAfter = MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread());

        assertTrue(allocBefore < allocMiddle);
        assertEquals(allocBefore, allocAfter);

        //assertEquals(0, cachedBefore);
        //assertEquals(0, cachedMiddle);
        //assertEquals(shape.getNumberOfBytes(), cachedAfter);

        assertEquals(cachedBefore, cachedMiddle);
        assertTrue(cachedBefore < cachedAfter);
    }

    @Test
    public void testCyclicCreation() throws Exception {
        Nd4j.create(100);

        log.info("Approximate free memory: {}", MemoryTracker.getInstance().getApproximateFreeMemory(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
        log.info("Real free memory: {}", MemoryTracker.getInstance().getPreciseFreeMemory(Nd4j.getAffinityManager().getDeviceForCurrentThread()));

        val timeStart = System.currentTimeMillis();

        while (true) {
            //val array = Nd4j.create(DataType.FLOAT, 1000, 1000);
            val array = Nd4j.create(DataType.FLOAT, RandomUtils.nextInt(100, 1000), RandomUtils.nextInt(100, 1000));

            val timeEnd = System.currentTimeMillis();
            if (timeEnd - timeStart > 5 * 60 * 1000) {
                log.info("Exiting...");
                break;
            }
        }

        while (true) {
            log.info("Cached device memory: {}", MemoryTracker.getInstance().getCachedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
            log.info("Active device memory: {}", MemoryTracker.getInstance().getAllocatedAmount(Nd4j.getAffinityManager().getDeviceForCurrentThread()));
            log.info("Cached host memory: {}", MemoryTracker.getInstance().getCachedHostAmount());
            log.info("Active host memory: {}", MemoryTracker.getInstance().getAllocatedHostAmount());

            System.gc();
            Thread.sleep(30000);
        }
    }
}
