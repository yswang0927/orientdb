package com.orientechnologies.orient.core.storage.cluster;

import com.orientechnologies.orient.core.db.OrientDB;
import com.orientechnologies.orient.core.storage.OPhysicalPosition;
import com.orientechnologies.orient.core.storage.ORawBuffer;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperationsManager;
import org.assertj.core.api.Assertions;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

public abstract class LocalPaginatedClusterAbstract {
  protected static String            buildDirectory;
  protected static OPaginatedCluster paginatedCluster;

  protected static String                    dbName;
  protected static OrientDB                  orientDB;
  protected static OAbstractPaginatedStorage storage;

  @AfterClass
  public static void afterClass() throws IOException {
    purgeCluster();

    paginatedCluster.delete();

    orientDB.drop(dbName);
  }

  @Before
  public void beforeMethod() throws IOException {
    purgeCluster();
  }

  private static void purgeCluster() throws IOException {
    if (paginatedCluster == null) {
      return;
    }

    OPhysicalPosition[] positions = paginatedCluster.ceilingPositions(new OPhysicalPosition(0));

    while (positions.length > 0) {
      for (OPhysicalPosition position : positions) {
        paginatedCluster.deleteRecord(position.clusterPosition);
        positions = paginatedCluster.higherPositions(positions[0]);
      }
    }
  }

  @Test
  public void testDeleteRecordAndAddNewOnItsPlace() throws IOException {
    byte[] smallRecord = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };
    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 1, null);
    Assert.assertEquals(physicalPosition.clusterPosition, 0);
    paginatedCluster.deleteRecord(physicalPosition.clusterPosition);

    recordVersion = 0;
    Assert.assertEquals(recordVersion, 0);
    physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 1, null);
    Assert.assertEquals(physicalPosition.clusterPosition, 1);

    Assert.assertEquals(physicalPosition.recordVersion, recordVersion);
  }

  @Test
  public void testAddOneSmallRecord() throws IOException {
    byte[] smallRecord = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };
    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();
    atomicOperationsManager.startAtomicOperation((String) null, true);

    {
      OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 1, null);
      Assert.assertEquals(physicalPosition.clusterPosition, 0);

      {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(smallRecord);
        Assert.assertEquals(rawBuffer.recordType, 1);
      }

      atomicOperationsManager.endAtomicOperation(false);

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(smallRecord);
        Assert.assertEquals(rawBuffer.recordType, 1);
      }
    }

    {
      atomicOperationsManager.startAtomicOperation((String) null, true);

      OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 1, null);
      Assert.assertEquals(physicalPosition.clusterPosition, 1);

      atomicOperationsManager.endAtomicOperation(true);

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
      Assert.assertNull(rawBuffer);
      Assert.assertEquals(1, paginatedCluster.getEntries());
    }
  }

  @Test
  public void testAddOneBigRecord() throws IOException {
    byte[] bigRecord = new byte[2 * 65536 + 100];
    Random mersenneTwisterFast = new Random();
    mersenneTwisterFast.nextBytes(bigRecord);

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

    {
      atomicOperationsManager.startAtomicOperation((String) null, true);

      OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 1, null);
      Assert.assertEquals(physicalPosition.clusterPosition, 0);

      atomicOperationsManager.endAtomicOperation(false);

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(rawBuffer.version, recordVersion);
      Assertions.assertThat(rawBuffer.buffer).isEqualTo(bigRecord);
      Assert.assertEquals(rawBuffer.recordType, 1);
    }

    {
      atomicOperationsManager.startAtomicOperation((String) null, true);

      OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 1, null);
      Assert.assertEquals(physicalPosition.clusterPosition, 1);

      atomicOperationsManager.endAtomicOperation(true);

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
      Assert.assertNull(rawBuffer);

      Assert.assertEquals(1, paginatedCluster.getEntries());
    }
  }

  @Test
  public void testAddManySmallRecords() throws IOException {
    final int records = 10_000;

    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);
    System.out.println("testAddManySmallRecords seed : " + seed);

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

    final int txCount = 1_000;

    {
      Map<Long, byte[]> positionRecordMap = new HashMap<>();
      boolean pendingTx = false;

      for (int i = 0; i < records; i++) {
        if (i % txCount == 0) {
          atomicOperationsManager.startAtomicOperation((String) null, true);
          pendingTx = true;
        }

        int recordSize = mersenneTwisterFast.nextInt(OClusterPage.MAX_RECORD_SIZE - 1) + 1;
        byte[] smallRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(smallRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);

        if ((i + 1) % txCount == 0) {
          atomicOperationsManager.endAtomicOperation(false);
          pendingTx = false;
        }
      }

      if (pendingTx) {
        atomicOperationsManager.endAtomicOperation(false);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);

        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }

    final long entriesCount = paginatedCluster.getEntries();

    {
      Map<Long, byte[]> positionRecordMap = new HashMap<>();

      boolean pendingTx = false;

      for (int i = 0; i < records; i++) {
        if (i % txCount == 0) {
          atomicOperationsManager.startAtomicOperation((String) null, true);
          pendingTx = true;
        }

        int recordSize = mersenneTwisterFast.nextInt(OClusterPage.MAX_RECORD_SIZE - 1) + 1;
        byte[] smallRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(smallRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 2, null);
        positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);

        if ((i + 1) % txCount == 0) {
          atomicOperationsManager.endAtomicOperation(true);
          pendingTx = false;
        }
      }

      if (pendingTx) {
        atomicOperationsManager.endAtomicOperation(true);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNull(rawBuffer);
      }

      Assert.assertEquals(entriesCount, paginatedCluster.getEntries());
    }
  }

  @Test
  public void testAddManyBigRecords() throws IOException {
    final int records = 5_000;

    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);

    System.out.println("testAddManyBigRecords seed : " + seed);

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

    final int txCount = 1_000;
    {
      boolean pendingTx = false;

      Map<Long, byte[]> positionRecordMap = new HashMap<>();
      for (int i = 0; i < records; i++) {
        if (i % txCount == 0) {
          atomicOperationsManager.startAtomicOperation((String) null, true);
          pendingTx = true;
        }

        int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + OClusterPage.MAX_RECORD_SIZE + 1;
        byte[] bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);

        if ((i + 1) % txCount == 0) {
          atomicOperationsManager.endAtomicOperation(false);
          pendingTx = false;
        }
      }

      if (pendingTx) {
        atomicOperationsManager.endAtomicOperation(false);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }

    final long entriesCount = paginatedCluster.getEntries();

    {
      boolean pendingTx = false;

      Map<Long, byte[]> positionRecordMap = new HashMap<>();
      for (int i = 0; i < records; i++) {

        if (i % txCount == 0) {
          atomicOperationsManager.startAtomicOperation((String) null, true);
          pendingTx = true;
        }

        int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + OClusterPage.MAX_RECORD_SIZE + 1;
        byte[] bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);

        if ((i + 1) % txCount == 0) {
          atomicOperationsManager.endAtomicOperation(true);
          pendingTx = false;
        }
      }

      if (pendingTx) {
        atomicOperationsManager.endAtomicOperation(true);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNull(rawBuffer);
      }

      Assert.assertEquals(entriesCount, paginatedCluster.getEntries());
    }
  }

  @Test
  public void testAddManyRecords() throws IOException {
    final int records = 10000;
    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);

    System.out.println("testAddManyRecords seed : " + seed);

    final int txCount = 1_000;
    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

    {
      Map<Long, byte[]> positionRecordMap = new HashMap<>();

      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      boolean pendingTx = false;

      for (int i = 0; i < records; i++) {
        if (i % txCount == 0) {
          atomicOperationsManager.startAtomicOperation((String) null, true);
          pendingTx = true;
        }

        int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + 1;
        byte[] smallRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(smallRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);

        if ((i + 1) % txCount == 0) {
          atomicOperationsManager.endAtomicOperation(false);
          pendingTx = false;
        }
      }

      if (pendingTx) {
        atomicOperationsManager.endAtomicOperation(false);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }

    final long entriesCount = paginatedCluster.getEntries();

    {
      Map<Long, byte[]> positionRecordMap = new HashMap<>();

      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      boolean pendingTx = false;

      for (int i = 0; i < records; i++) {
        if (i % txCount == 0) {
          atomicOperationsManager.startAtomicOperation((String) null, true);
          pendingTx = true;
        }

        int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + 1;
        byte[] smallRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(smallRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);

        if ((i + 1) % txCount == 0) {
          atomicOperationsManager.endAtomicOperation(true);
          pendingTx = false;
        }
      }

      if (pendingTx) {
        atomicOperationsManager.endAtomicOperation(true);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNull(rawBuffer);
      }

      Assert.assertEquals(entriesCount, paginatedCluster.getEntries());
    }
  }

  @Test
  public void testAllocatePositionMap() throws IOException {

    OPhysicalPosition position = paginatedCluster.allocatePosition((byte) 'd');
    Assert.assertTrue(position.clusterPosition >= 0);
    ORawBuffer rec = paginatedCluster.readRecord(position.clusterPosition, false);
    Assert.assertNull(rec);
    paginatedCluster.createRecord(new byte[20], 1, (byte) 'd', position);
    rec = paginatedCluster.readRecord(position.clusterPosition, false);
    Assert.assertNotNull(rec);
  }

  @Test
  public void testManyAllocatePositionMap() throws IOException {
    final int records = 10000;

    List<OPhysicalPosition> positions = new ArrayList<>();
    for (int i = 0; i < records; i++) {
      OPhysicalPosition position = paginatedCluster.allocatePosition((byte) 'd');
      Assert.assertTrue(position.clusterPosition >= 0);
      ORawBuffer rec = paginatedCluster.readRecord(position.clusterPosition, false);
      Assert.assertNull(rec);
      positions.add(position);
    }

    for (int i = 0; i < records; i++) {
      OPhysicalPosition position = positions.get(i);
      paginatedCluster.createRecord(new byte[20], 1, (byte) 'd', position);
      ORawBuffer rec = paginatedCluster.readRecord(position.clusterPosition, false);
      Assert.assertNotNull(rec);
    }
  }

  @Test
  public void testRemoveHalfSmallRecords() throws IOException {
    final int records = 10_000;
    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);

    System.out.println("testRemoveHalfSmallRecords seed : " + seed);
    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();
    final int txCount = 1_000;

    {
      Map<Long, byte[]> positionRecordMap = new HashMap<>();

      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      for (int i = 0; i < records; i++) {
        int recordSize = mersenneTwisterFast.nextInt(OClusterPage.MAX_RECORD_SIZE - 1) + 1;
        byte[] smallRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(smallRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);
      }

      int deletedRecords = 0;
      boolean pendingTxOperations = false;

      Assert.assertEquals(records, paginatedCluster.getEntries());
      Set<Long> deletedPositions = new HashSet<>();
      Iterator<Long> positionIterator = positionRecordMap.keySet().iterator();
      while (positionIterator.hasNext()) {
        long clusterPosition = positionIterator.next();
        if (mersenneTwisterFast.nextBoolean()) {
          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.startAtomicOperation((String) null, true);
            pendingTxOperations = true;
          }

          deletedPositions.add(clusterPosition);
          Assert.assertTrue(paginatedCluster.deleteRecord(clusterPosition));
          deletedRecords++;

          Assert.assertEquals(records - deletedRecords, paginatedCluster.getEntries());

          positionIterator.remove();
          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.endAtomicOperation(false);
            pendingTxOperations = false;
          }
        }
      }

      if (pendingTxOperations) {
        atomicOperationsManager.endAtomicOperation(false);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      Assert.assertEquals(paginatedCluster.getEntries(), records - deletedRecords);
      for (long deletedPosition : deletedPositions) {
        Assert.assertNull(paginatedCluster.readRecord(deletedPosition, false));
        Assert.assertFalse(paginatedCluster.deleteRecord(deletedPosition));
      }

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }

    final long entriesCount = paginatedCluster.getEntries();

    {
      Map<Long, byte[]> positionRecordMap = new HashMap<>();

      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      for (int i = 0; i < records; i++) {
        int recordSize = mersenneTwisterFast.nextInt(OClusterPage.MAX_RECORD_SIZE - 1) + 1;
        byte[] smallRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(smallRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);
      }

      int deletedRecords = 0;
      boolean pendingTxOperations = false;

      Assert.assertEquals(records + entriesCount, paginatedCluster.getEntries());

      Iterator<Long> positionIterator = positionRecordMap.keySet().iterator();
      while (positionIterator.hasNext()) {
        long clusterPosition = positionIterator.next();
        if (mersenneTwisterFast.nextBoolean()) {
          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.startAtomicOperation((String) null, true);
            pendingTxOperations = true;
          }

          Assert.assertTrue(paginatedCluster.deleteRecord(clusterPosition));
          deletedRecords++;

          positionIterator.remove();
          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.endAtomicOperation(true);
            Assert.assertEquals(paginatedCluster.getEntries(), records + entriesCount);

            pendingTxOperations = false;
          }
        }
      }

      if (pendingTxOperations) {
        atomicOperationsManager.endAtomicOperation(true);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      Assert.assertEquals(paginatedCluster.getEntries(), records + entriesCount);

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);
        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }

  }

  @Test
  public void testRemoveHalfBigRecords() throws IOException {
    final int records = 5_000;
    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);

    System.out.println("testRemoveHalfBigRecords seed : " + seed);

    final int txCount = 1_000;
    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

    {
      Map<Long, byte[]> positionRecordMap = new HashMap<>();

      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      boolean pendingTxOperations = false;

      for (int i = 0; i < records; i++) {
        int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + OClusterPage.MAX_RECORD_SIZE + 1;

        byte[] bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
      }

      int deletedRecords = 0;
      Assert.assertEquals(records, paginatedCluster.getEntries());
      Set<Long> deletedPositions = new HashSet<>();
      Iterator<Long> positionIterator = positionRecordMap.keySet().iterator();
      while (positionIterator.hasNext()) {
        long clusterPosition = positionIterator.next();
        if (mersenneTwisterFast.nextBoolean()) {
          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.startAtomicOperation((String) null, true);
            pendingTxOperations = true;
          }

          deletedPositions.add(clusterPosition);
          Assert.assertTrue(paginatedCluster.deleteRecord(clusterPosition));
          deletedRecords++;

          Assert.assertEquals(records - deletedRecords, paginatedCluster.getEntries());

          positionIterator.remove();

          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.endAtomicOperation(false);
            pendingTxOperations = false;
          }
        }
      }

      if (pendingTxOperations) {
        atomicOperationsManager.endAtomicOperation(false);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      Assert.assertEquals(paginatedCluster.getEntries(), records - deletedRecords);
      for (long deletedPosition : deletedPositions) {
        Assert.assertNull(paginatedCluster.readRecord(deletedPosition, false));
        Assert.assertFalse(paginatedCluster.deleteRecord(deletedPosition));
      }

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);

        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }

    final long entriesCount = paginatedCluster.getEntries();

    {
      Map<Long, byte[]> positionRecordMap = new HashMap<>();

      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      boolean pendingTxOperations = false;

      for (int i = 0; i < records; i++) {
        int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + OClusterPage.MAX_RECORD_SIZE + 1;

        byte[] bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
      }

      int deletedRecords = 0;
      Assert.assertEquals(records + entriesCount, paginatedCluster.getEntries());
      Iterator<Long> positionIterator = positionRecordMap.keySet().iterator();
      while (positionIterator.hasNext()) {
        long clusterPosition = positionIterator.next();
        if (mersenneTwisterFast.nextBoolean()) {
          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.startAtomicOperation((String) null, true);
            pendingTxOperations = true;
          }

          Assert.assertTrue(paginatedCluster.deleteRecord(clusterPosition));
          deletedRecords++;

          positionIterator.remove();

          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.endAtomicOperation(true);
            pendingTxOperations = false;
          }
        }
      }

      if (pendingTxOperations) {
        atomicOperationsManager.endAtomicOperation(true);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      Assert.assertEquals(paginatedCluster.getEntries(), records + entriesCount);

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);

        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }
  }

  @Test
  public void testRemoveHalfRecords() throws IOException {
    final int records = 10_000;
    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);

    System.out.println("testRemoveHalfRecords seed : " + seed);

    final int txCount = 1_000;
    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

    {
      Map<Long, byte[]> positionRecordMap = new HashMap<>();

      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      for (int i = 0; i < records; i++) {
        int recordSize = mersenneTwisterFast.nextInt(3 * OClusterPage.MAX_RECORD_SIZE) + 1;

        byte[] bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
      }

      int deletedRecords = 0;
      Assert.assertEquals(records, paginatedCluster.getEntries());
      Set<Long> deletedPositions = new HashSet<>();
      Iterator<Long> positionIterator = positionRecordMap.keySet().iterator();
      boolean pendingTxOperations = false;

      while (positionIterator.hasNext()) {
        long clusterPosition = positionIterator.next();
        if (mersenneTwisterFast.nextBoolean()) {
          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.startAtomicOperation((String) null, true);
            pendingTxOperations = true;
          }

          deletedPositions.add(clusterPosition);
          Assert.assertTrue(paginatedCluster.deleteRecord(clusterPosition));
          deletedRecords++;

          Assert.assertEquals(records - deletedRecords, paginatedCluster.getEntries());

          positionIterator.remove();

          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.endAtomicOperation(false);
            pendingTxOperations = false;
          }
        }
      }

      if (pendingTxOperations) {
        atomicOperationsManager.endAtomicOperation(false);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      Assert.assertEquals(paginatedCluster.getEntries(), records - deletedRecords);
      for (long deletedPosition : deletedPositions) {
        Assert.assertNull(paginatedCluster.readRecord(deletedPosition, false));
        Assert.assertFalse(paginatedCluster.deleteRecord(deletedPosition));
      }

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);

        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }

    final long entriesCount = paginatedCluster.getEntries();

    {
      Map<Long, byte[]> positionRecordMap = new HashMap<>();

      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      for (int i = 0; i < records; i++) {
        int recordSize = mersenneTwisterFast.nextInt(3 * OClusterPage.MAX_RECORD_SIZE) + 1;

        byte[] bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
      }

      int deletedRecords = 0;
      Assert.assertEquals(records + entriesCount, paginatedCluster.getEntries());
      Iterator<Long> positionIterator = positionRecordMap.keySet().iterator();
      boolean pendingTxOperations = false;

      while (positionIterator.hasNext()) {
        long clusterPosition = positionIterator.next();
        if (mersenneTwisterFast.nextBoolean()) {
          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.startAtomicOperation((String) null, true);
            pendingTxOperations = true;
          }

          Assert.assertTrue(paginatedCluster.deleteRecord(clusterPosition));
          deletedRecords++;

          positionIterator.remove();

          if (deletedRecords % txCount == 0) {
            atomicOperationsManager.endAtomicOperation(true);
            pendingTxOperations = false;
          }
        }
      }

      if (pendingTxOperations) {
        atomicOperationsManager.endAtomicOperation(true);
      }

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      Assert.assertEquals(paginatedCluster.getEntries(), records + entriesCount);

      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(rawBuffer);

        Assert.assertEquals(rawBuffer.version, recordVersion);

        Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }
  }

  @Test
  public void testRemoveHalfRecordsAndAddAnotherHalfAgain() throws IOException {
    final int records = 10_000;
    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);

    System.out.println("testRemoveHalfRecordsAndAddAnotherHalfAgain seed : " + seed);

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();
    Map<Long, byte[]> positionRecordMap = new HashMap<>();
    {
      for (int i = 0; i < records; i++) {
        int recordSize = mersenneTwisterFast.nextInt(3 * OClusterPage.MAX_RECORD_SIZE) + 1;

        byte[] bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
      }

      atomicOperationsManager.startAtomicOperation((String) null, true);

      int deletedRecords = 0;
      Assert.assertEquals(records, paginatedCluster.getEntries());

      Iterator<Long> positionIterator = positionRecordMap.keySet().iterator();
      while (positionIterator.hasNext()) {
        long clusterPosition = positionIterator.next();
        if (mersenneTwisterFast.nextBoolean()) {
          Assert.assertTrue(paginatedCluster.deleteRecord(clusterPosition));
          deletedRecords++;

          Assert.assertEquals(paginatedCluster.getEntries(), records - deletedRecords);

          positionIterator.remove();
        }
      }

      Assert.assertEquals(paginatedCluster.getEntries(), records - deletedRecords);

      for (int i = 0; i < records / 2; i++) {
        int recordSize = mersenneTwisterFast.nextInt(3 * OClusterPage.MAX_RECORD_SIZE) + 1;

        byte[] bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 2, null);

        positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
      }

      atomicOperationsManager.endAtomicOperation(false);

      Assert.assertEquals(paginatedCluster.getEntries(), positionRecordMap.size());
      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        final ORawBuffer buffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(buffer);

        Assert.assertArrayEquals(entry.getValue(), buffer.buffer);
      }
    }

    {
      atomicOperationsManager.startAtomicOperation((String) null, true);

      int deletedRecords = 0;
      Assert.assertEquals(positionRecordMap.size(), paginatedCluster.getEntries());

      for (final long clusterPosition : positionRecordMap.keySet()) {
        if (mersenneTwisterFast.nextBoolean()) {
          Assert.assertTrue(paginatedCluster.deleteRecord(clusterPosition));
          deletedRecords++;

          Assert.assertEquals(paginatedCluster.getEntries(), positionRecordMap.size() - deletedRecords);
        }
      }

      Assert.assertEquals(paginatedCluster.getEntries(), positionRecordMap.size() - deletedRecords);

      for (int i = 0; i < records / 2; i++) {
        int recordSize = mersenneTwisterFast.nextInt(3 * OClusterPage.MAX_RECORD_SIZE) + 1;

        byte[] bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 2, null);
      }

      atomicOperationsManager.endAtomicOperation(true);

      Assert.assertEquals(paginatedCluster.getEntries(), positionRecordMap.size());
      for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
        final ORawBuffer buffer = paginatedCluster.readRecord(entry.getKey(), false);
        Assert.assertNotNull(buffer);

        Assert.assertArrayEquals(entry.getValue(), buffer.buffer);
      }

    }
  }

  @Test
  public void testUpdateOneSmallRecord() throws IOException {
    {
      byte[] smallRecord = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };

      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 1, null);
      Assert.assertEquals(physicalPosition.clusterPosition, 0);

      recordVersion++;
      smallRecord = new byte[] { 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3 };
      paginatedCluster.updateRecord(physicalPosition.clusterPosition, smallRecord, recordVersion, (byte) 2);

      ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(rawBuffer.version, recordVersion);

      Assertions.assertThat(rawBuffer.buffer).isEqualTo(smallRecord);
      Assert.assertEquals(rawBuffer.recordType, 2);
    }
    {
      OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

      final byte[] initialSmallRecord = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };

      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      OPhysicalPosition physicalPosition = paginatedCluster.createRecord(initialSmallRecord, recordVersion, (byte) 1, null);
      Assert.assertEquals(physicalPosition.clusterPosition, 1);

      recordVersion++;
      final byte[] updatedSmallRecord = new byte[] { 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3 };

      atomicOperationsManager.startAtomicOperation((String) null, true);
      paginatedCluster.updateRecord(physicalPosition.clusterPosition, updatedSmallRecord, recordVersion, (byte) 2);
      atomicOperationsManager.endAtomicOperation(true);

      ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(rawBuffer.version, recordVersion - 1);

      Assertions.assertThat(rawBuffer.buffer).isEqualTo(initialSmallRecord);
      Assert.assertEquals(rawBuffer.recordType, 1);
    }
  }

  @Test
  public void testUpdateOneSmallRecordVersionIsLowerCurrentOne() throws IOException {
    {
      byte[] smallRecord = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };
      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 1, null);
      Assert.assertEquals(physicalPosition.clusterPosition, 0);

      int updateRecordVersion = 0;
      updateRecordVersion++;

      smallRecord = new byte[] { 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3 };
      paginatedCluster.updateRecord(physicalPosition.clusterPosition, smallRecord, updateRecordVersion, (byte) 2);

      ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(rawBuffer.version, updateRecordVersion);

      Assertions.assertThat(rawBuffer.buffer).isEqualTo(smallRecord);
      Assert.assertEquals(rawBuffer.recordType, 2);
    }

    {
      final byte[] initialSmallRecord = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };
      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();

      OPhysicalPosition physicalPosition = paginatedCluster.createRecord(initialSmallRecord, recordVersion, (byte) 1, null);
      Assert.assertEquals(1, physicalPosition.clusterPosition);

      int updateRecordVersion = 0;
      updateRecordVersion++;

      final byte[] updateSmallRecord = new byte[] { 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3 };

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      atomicOperationsManager.startAtomicOperation((String) null, true);
      paginatedCluster.updateRecord(physicalPosition.clusterPosition, updateSmallRecord, updateRecordVersion, (byte) 2);
      atomicOperationsManager.endAtomicOperation(true);

      assert OAtomicOperationsManager.getCurrentOperation() == null;

      ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(rawBuffer.version, recordVersion);

      Assertions.assertThat(rawBuffer.buffer).isEqualTo(initialSmallRecord);
      Assert.assertEquals(rawBuffer.recordType, 1);
    }
  }

  @Test
  public void testUpdateOneSmallRecordVersionIsMinusTwo() throws IOException {
    {
      byte[] smallRecord = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };
      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 1, null);
      Assert.assertEquals(physicalPosition.clusterPosition, 0);

      int updateRecordVersion;
      updateRecordVersion = -2;

      smallRecord = new byte[] { 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3 };
      paginatedCluster.updateRecord(physicalPosition.clusterPosition, smallRecord, updateRecordVersion, (byte) 2);

      ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(rawBuffer.version, updateRecordVersion);
      Assertions.assertThat(rawBuffer.buffer).isEqualTo(smallRecord);

      Assert.assertEquals(rawBuffer.recordType, 2);
    }
    {
      byte[] initialSmallRecord = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 0 };
      int recordVersion = 0;
      recordVersion++;
      recordVersion++;

      OPhysicalPosition physicalPosition = paginatedCluster.createRecord(initialSmallRecord, recordVersion, (byte) 1, null);
      Assert.assertEquals(physicalPosition.clusterPosition, 1);

      int updateRecordVersion;
      updateRecordVersion = -2;

      final OAtomicOperationsManager atomicOperationsManager = storage.getAtomicOperationsManager();
      atomicOperationsManager.startAtomicOperation((String) null, true);
      final byte[] updatedSmallRecord = new byte[] { 2, 3, 4, 5, 6, 7, 8, 9, 0, 1, 2, 3 };
      paginatedCluster.updateRecord(physicalPosition.clusterPosition, updatedSmallRecord, updateRecordVersion, (byte) 2);
      atomicOperationsManager.endAtomicOperation(true);

      ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
      Assert.assertNotNull(rawBuffer);

      Assert.assertEquals(rawBuffer.version, recordVersion);
      Assertions.assertThat(rawBuffer.buffer).isEqualTo(initialSmallRecord);

      Assert.assertEquals(rawBuffer.recordType, 1);
    }
  }

  @Test
  public void testUpdateOneBigRecord() throws IOException {
    byte[] bigRecord = new byte[2 * 65536 + 100];
    Random mersenneTwisterFast = new Random();
    mersenneTwisterFast.nextBytes(bigRecord);

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 1, null);
    Assert.assertEquals(physicalPosition.clusterPosition, 0);

    recordVersion++;
    bigRecord = new byte[2 * 65536 + 20];
    mersenneTwisterFast.nextBytes(bigRecord);

    paginatedCluster.updateRecord(physicalPosition.clusterPosition, bigRecord, recordVersion, (byte) 2);

    ORawBuffer rawBuffer = paginatedCluster.readRecord(physicalPosition.clusterPosition, false);
    Assert.assertNotNull(rawBuffer);

    Assert.assertEquals(rawBuffer.version, recordVersion);
    //    Assert.assertEquals(rawBuffer.buffer, bigRecord);

    Assertions.assertThat(rawBuffer.buffer).isEqualTo(bigRecord);
    Assert.assertEquals(rawBuffer.recordType, 2);
  }

  @Test
  public void testUpdateManySmallRecords() throws IOException {
    final int records = 10000;

    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);
    System.out.println("testUpdateManySmallRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();
    Set<Long> updatedPositions = new HashSet<>();

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    for (int i = 0; i < records; i++) {
      int recordSize = mersenneTwisterFast.nextInt(OClusterPage.MAX_RECORD_SIZE - 1) + 1;
      byte[] smallRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(smallRecord);

      final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(smallRecord, recordVersion, (byte) 2, null);

      positionRecordMap.put(physicalPosition.clusterPosition, smallRecord);
    }

    int newRecordVersion;
    newRecordVersion = recordVersion;
    newRecordVersion++;

    for (long clusterPosition : positionRecordMap.keySet()) {
      if (mersenneTwisterFast.nextBoolean()) {
        int recordSize = mersenneTwisterFast.nextInt(OClusterPage.MAX_RECORD_SIZE - 1) + 1;
        byte[] smallRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(smallRecord);

        paginatedCluster.updateRecord(clusterPosition, smallRecord, newRecordVersion, (byte) 3);

        positionRecordMap.put(clusterPosition, smallRecord);
        updatedPositions.add(clusterPosition);
      }
    }

    for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
      ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
      Assert.assertNotNull(rawBuffer);

      //      Assert.assertEquals(rawBuffer.buffer, entry.getValue());

      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());

      if (updatedPositions.contains(entry.getKey())) {
        Assert.assertEquals(rawBuffer.version, newRecordVersion);
        Assert.assertEquals(rawBuffer.recordType, 3);
      } else {
        Assert.assertEquals(rawBuffer.version, recordVersion);
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }
  }

  @Test
  public void testUpdateManyBigRecords() throws IOException {
    final int records = 5000;

    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);
    System.out.println("testUpdateManyBigRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();
    Set<Long> updatedPositions = new HashSet<>();

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    for (int i = 0; i < records; i++) {
      int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + OClusterPage.MAX_RECORD_SIZE + 1;
      byte[] bigRecord = new byte[recordSize];
      mersenneTwisterFast.nextBytes(bigRecord);

      final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(bigRecord, recordVersion, (byte) 2, null);
      positionRecordMap.put(physicalPosition.clusterPosition, bigRecord);
    }

    int newRecordVersion;
    newRecordVersion = recordVersion;
    newRecordVersion++;

    for (long clusterPosition : positionRecordMap.keySet()) {
      if (mersenneTwisterFast.nextBoolean()) {
        int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + OClusterPage.MAX_RECORD_SIZE + 1;
        byte[] bigRecord = new byte[recordSize];
        mersenneTwisterFast.nextBytes(bigRecord);

        paginatedCluster.updateRecord(clusterPosition, bigRecord, newRecordVersion, (byte) 3);

        positionRecordMap.put(clusterPosition, bigRecord);
        updatedPositions.add(clusterPosition);
      }
    }

    for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
      ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
      Assert.assertNotNull(rawBuffer);

      //      Assert.assertEquals(rawBuffer.buffer, entry.getValue());

      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());

      if (updatedPositions.contains(entry.getKey())) {
        Assert.assertEquals(rawBuffer.version, newRecordVersion);

        Assert.assertEquals(rawBuffer.recordType, 3);
      } else {
        Assert.assertEquals(rawBuffer.version, recordVersion);
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }
  }

  @Test
  public void testUpdateManyRecords() throws IOException {
    final int records = 10000;

    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);
    System.out.println("testUpdateManyRecords seed : " + seed);

    Map<Long, byte[]> positionRecordMap = new HashMap<>();
    Set<Long> updatedPositions = new HashSet<>();

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    for (int i = 0; i < records; i++) {
      int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + 1;
      byte[] record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(record, recordVersion, (byte) 2, null);
      positionRecordMap.put(physicalPosition.clusterPosition, record);
    }

    int newRecordVersion;
    newRecordVersion = recordVersion;
    newRecordVersion++;

    for (long clusterPosition : positionRecordMap.keySet()) {
      if (mersenneTwisterFast.nextBoolean()) {
        int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + 1;
        byte[] record = new byte[recordSize];
        mersenneTwisterFast.nextBytes(record);

        paginatedCluster.updateRecord(clusterPosition, record, newRecordVersion, (byte) 3);

        positionRecordMap.put(clusterPosition, record);
        updatedPositions.add(clusterPosition);
      }
    }

    for (Map.Entry<Long, byte[]> entry : positionRecordMap.entrySet()) {
      ORawBuffer rawBuffer = paginatedCluster.readRecord(entry.getKey(), false);
      Assert.assertNotNull(rawBuffer);

      //      Assert.assertEquals(rawBuffer.buffer, entry.getValue());

      Assertions.assertThat(rawBuffer.buffer).isEqualTo(entry.getValue());

      if (updatedPositions.contains(entry.getKey())) {
        Assert.assertEquals(rawBuffer.version, newRecordVersion);
        Assert.assertEquals(rawBuffer.recordType, 3);
      } else {
        Assert.assertEquals(rawBuffer.version, recordVersion);
        Assert.assertEquals(rawBuffer.recordType, 2);
      }
    }
  }

  @Test
  public void testForwardIteration() throws IOException {
    final int records = 10000;

    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);
    System.out.println("testForwardIteration seed : " + seed);

    NavigableMap<Long, byte[]> positionRecordMap = new TreeMap<>();

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    for (int i = 0; i < records; i++) {
      int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + 1;
      byte[] record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(record, recordVersion, (byte) 2, null);
      positionRecordMap.put(physicalPosition.clusterPosition, record);
    }

    Iterator<Long> positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long clusterPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        Assert.assertTrue(paginatedCluster.deleteRecord(clusterPosition));
        positionIterator.remove();
      }
    }

    OPhysicalPosition physicalPosition = new OPhysicalPosition();
    physicalPosition.clusterPosition = 0;

    OPhysicalPosition[] positions = paginatedCluster.ceilingPositions(physicalPosition);
    Assert.assertTrue(positions.length > 0);

    int counter = 0;
    for (long testedPosition : positionRecordMap.keySet()) {
      Assert.assertTrue(positions.length > 0);
      Assert.assertEquals(positions[0].clusterPosition, testedPosition);

      OPhysicalPosition positionToFind = positions[0];
      positions = paginatedCluster.higherPositions(positionToFind);

      counter++;
    }

    Assert.assertEquals(paginatedCluster.getEntries(), counter);

    Assert.assertEquals(paginatedCluster.getFirstPosition(), (long) positionRecordMap.firstKey());
    Assert.assertEquals(paginatedCluster.getLastPosition(), (long) positionRecordMap.lastKey());
  }

  @Test
  public void testBackwardIteration() throws IOException {
    final int records = 10000;

    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);
    System.out.println("testBackwardIteration seed : " + seed);

    NavigableMap<Long, byte[]> positionRecordMap = new TreeMap<>();

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    for (int i = 0; i < records; i++) {
      int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + 1;
      byte[] record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);

      final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(record, recordVersion, (byte) 2, null);
      positionRecordMap.put(physicalPosition.clusterPosition, record);
    }

    Iterator<Long> positionIterator = positionRecordMap.keySet().iterator();
    while (positionIterator.hasNext()) {
      long clusterPosition = positionIterator.next();
      if (mersenneTwisterFast.nextBoolean()) {
        Assert.assertTrue(paginatedCluster.deleteRecord(clusterPosition));
        positionIterator.remove();
      }
    }

    OPhysicalPosition physicalPosition = new OPhysicalPosition();
    physicalPosition.clusterPosition = Long.MAX_VALUE;

    OPhysicalPosition[] positions = paginatedCluster.floorPositions(physicalPosition);
    Assert.assertTrue(positions.length > 0);

    positionIterator = positionRecordMap.descendingKeySet().iterator();
    int counter = 0;
    while (positionIterator.hasNext()) {
      Assert.assertTrue(positions.length > 0);

      long testedPosition = positionIterator.next();
      Assert.assertEquals(positions[positions.length - 1].clusterPosition, testedPosition);

      OPhysicalPosition positionToFind = positions[positions.length - 1];
      positions = paginatedCluster.lowerPositions(positionToFind);

      counter++;
    }

    Assert.assertEquals(paginatedCluster.getEntries(), counter);

    Assert.assertEquals(paginatedCluster.getFirstPosition(), (long) positionRecordMap.firstKey());
    Assert.assertEquals(paginatedCluster.getLastPosition(), (long) positionRecordMap.lastKey());
  }

  @Test
  public void testGetPhysicalPosition() throws IOException {
    final int records = 10000;

    long seed = System.currentTimeMillis();
    Random mersenneTwisterFast = new Random(seed);
    System.out.println("testGetPhysicalPosition seed : " + seed);

    Set<OPhysicalPosition> positions = new HashSet<>();

    int recordVersion = 0;
    recordVersion++;
    recordVersion++;

    for (int i = 0; i < records; i++) {
      int recordSize = mersenneTwisterFast.nextInt(2 * OClusterPage.MAX_RECORD_SIZE) + 1;
      byte[] record = new byte[recordSize];
      mersenneTwisterFast.nextBytes(record);
      recordVersion++;

      final OPhysicalPosition physicalPosition = paginatedCluster.createRecord(record, recordVersion, (byte) i, null);
      positions.add(physicalPosition);
    }

    Set<OPhysicalPosition> removedPositions = new HashSet<>();
    for (OPhysicalPosition position : positions) {
      OPhysicalPosition physicalPosition = new OPhysicalPosition();
      physicalPosition.clusterPosition = position.clusterPosition;

      physicalPosition = paginatedCluster.getPhysicalPosition(physicalPosition);

      Assert.assertEquals(physicalPosition.clusterPosition, position.clusterPosition);
      Assert.assertEquals(physicalPosition.recordType, position.recordType);

      Assert.assertEquals(physicalPosition.recordSize, position.recordSize);
      if (mersenneTwisterFast.nextBoolean()) {
        paginatedCluster.deleteRecord(position.clusterPosition);
        removedPositions.add(position);
      }
    }

    for (OPhysicalPosition position : positions) {
      OPhysicalPosition physicalPosition = new OPhysicalPosition();
      physicalPosition.clusterPosition = position.clusterPosition;

      physicalPosition = paginatedCluster.getPhysicalPosition(physicalPosition);

      if (removedPositions.contains(position))
        Assert.assertNull(physicalPosition);
      else {
        Assert.assertEquals(physicalPosition.clusterPosition, position.clusterPosition);
        Assert.assertEquals(physicalPosition.recordType, position.recordType);

        Assert.assertEquals(physicalPosition.recordSize, position.recordSize);
      }
    }
  }
}
