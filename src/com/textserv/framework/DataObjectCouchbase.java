package com.textserv.framework;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;

import net.spy.memcached.CASValue;
import net.spy.memcached.internal.OperationFuture;

import com.couchbase.client.CouchbaseClient;
import com.textserv.rulesengine.dataobject.JsDataObject;

public class DataObjectCouchbase {
	
	public static int oneHour = 60*60;
	public static int oneDay = oneHour * 24;
	public static int thirtyDays = oneDay *30;
	public static int sixtyDays = thirtyDays * 2;
	public static int ninetyDays = thirtyDays * 3;
	public static int forever = 0;
	public static int listPageSize = 1000;
	
	public static int normalizeTTL( int ttl ) {
		if ( ttl > thirtyDays ) {
			ttl += (int)System.currentTimeMillis();
		}
		return ttl;
	}
	
	public static int countTimeSeriesEntryDay(CouchbaseClient client, String key, DateTime startDate, DateTime endDate) {
		Collection<String> keys = generateTimeSeriesKeySetDay(key, startDate, endDate);
		Map<String,Object> keyvalues = client.getBulk(keys);
		int count = 0;
		for (Object value : keyvalues.values()) {
			count += ((String)value).split(",").length;
		}
		return count;
	}

	public static List<JsDataObject> findTimeSeriesJSEntryDay(CouchbaseClient client, String key, String objKeyPrefix, DateTime startDate, DateTime endDate) {
		Collection<String> keys = generateTimeSeriesKeySetDay(key, startDate, endDate);
		Map<String,Object> keyvalues = client.getBulk(keys);
		List<JsDataObject> results = new ArrayList<JsDataObject>();
		
		Collection<String> objKeys = new ArrayList<String>();

		for (Object value : keyvalues.values()) {
			for ( String id : ((String)value).split(",") ) {
				objKeys.add(objKeyPrefix + ":" + id);
			}
		}
		Map<String,Object> objvalues = client.getBulk(objKeys);
		for (Object value : objvalues.values()) {
			JsDataObject jsDo = new JsDataObject();
			jsDo.getWrappedObject().fromStringEncoded((String)value);
		}
		return results;
	}

	private static Collection<String> generateTimeSeriesKeySetDay(String key, DateTime startDate, DateTime endDate) {
		ArrayList<String> keys = new ArrayList<String>();
		SimpleDateFormat formatter = new SimpleDateFormat ("yyyy:MM:dd");

		DateTime theDate = startDate;
		do {
			keys.add(key + ": " + formatter.format(new Date(theDate.getMillis())));
			theDate = theDate.plusDays(1);
		} while ( theDate.isBefore(endDate));
				
		return keys;
	}

	public static void writeTimeSeriesEntryDay(CouchbaseClient client, String key, String keytoReference, Date timestamp, int ttl) {
		SimpleDateFormat formatter = new SimpleDateFormat ("yyyy:MM:dd");
		DataObjectCouchbase.writeTimeSeriesEntry(client, key, keytoReference, timestamp, ttl, formatter);
	}

	public static void writeTimeSeriesEntryHour(CouchbaseClient client, String key, String keytoReference, Date timestamp, int ttl) {
		SimpleDateFormat formatter = new SimpleDateFormat ("yyyy:MM.dd:hh");
		DataObjectCouchbase.writeTimeSeriesEntry(client, key, keytoReference, timestamp, ttl, formatter);
	}

	public static void writeTimeSeriesEntry(CouchbaseClient client, String key, String keytoReference, Date timestamp, int ttl, DateFormat formatter) {
		String dateKey = key + ":" + formatter.format(timestamp);
	    CASValue<Object> casValue = client.getAndTouch(dateKey, ttl);
	    if (casValue == null) {
	    	client.set(dateKey, ttl, keytoReference);
	    } else {
	    	OperationFuture<Boolean> appendDone = client.append(casValue.getCas(), dateKey, keytoReference + ",");
	        try {
	            if (!appendDone.get()) {
		    		throw new DataObjectException("CoucbaseWriteFailed.Inconsistent CAS");
	            }
	        } catch (Exception e) {
	        	throw new DataObjectException(e);
	        }
	    }
	}

	public static boolean writeIfNotExists( CouchbaseClient client, String key, String stringEncodedDataObject, int ttl) throws DataObjectException  {
		OperationFuture<Boolean> okToProcess = client.add(key, ttl, stringEncodedDataObject);
		boolean success = false;
		try {
			success = (okToProcess.get() == true);
		} catch( Exception e ) {
			throw new DataObjectException(e);
		}
		return success;
	}

	public static boolean writeIfNotExists( CouchbaseClient client, String key, DataObject dataObject, int ttl) throws DataObjectException  {
		dataObject.setDate("updated_at", new Date());
		OperationFuture<Boolean> okToProcess = client.add(key, ttl, dataObject.toStringEncoded());
		boolean success = false;
		try {
			success = (okToProcess.get() == true);
		} catch( Exception e ) {
			throw new DataObjectException(e);
		}
		return success;
	}

	public static Map<String, DataObject> getBulk(CouchbaseClient client, List<String> keys, String mapKeyField) {
		HashMap<String, DataObject> values = new HashMap<String, DataObject>();
		Map<String, Object> existings = client.getBulk(keys);
		for ( Object obj : existings.values() ) {
	    	DataObject existingObj = new DataObject();
	    	existingObj.fromStringEncoded((String)obj);
	    	values.put(existingObj.getString(mapKeyField), existingObj);
		}
		return values;
	}
	public static DataObject readAndMerge( CouchbaseClient client, String key, DataObject dataObject, String prefix) throws DataObjectException  {
		Object value = client.get(key);
		if (value != null) {
	    	DataObject existingObj = new DataObject();
	    	existingObj.fromStringEncoded((String)value);
			dataObject.merge( existingObj, true, prefix);			
		}
		return dataObject;
	}

	public static DataObject read( CouchbaseClient client, String key) throws DataObjectException  {
		Object value = client.get(key);
		DataObject existingObj = null;
		if (value != null) {
			existingObj  = new DataObject();
	    	existingObj.fromStringEncoded((String)value);
		}
		return existingObj;
	}

	public static DataObject readInto( CouchbaseClient client, String key, DataObject dataObject) throws DataObjectException  {
		Object value = client.get(key);
		if (value != null) {
			dataObject.fromStringEncoded((String)value);
		}
		return dataObject;
	}

	public static void appendDataObject(CouchbaseClient client, String key, DataObject dataObject, int ttl) throws DataObjectException {
		appendString( client, key, dataObject.toStringEncoded(), ttl);
	}
	
	public static void appendString(CouchbaseClient client, String key, String string, int ttl) throws DataObjectException {
		int retries = 0;
		boolean success = false;
		while ( !success && retries < 5 ) {
			try {
				retries++;
				CASValue<Object> casValue = client.gets(key);
				if (casValue == null) {
					OperationFuture<Boolean> addOp = client.add(key, ttl, string);
					if ( addOp.get().booleanValue()) {
						success = true;						
					}
				} else {
					OperationFuture<Boolean> appendOp = client.append(casValue.getCas(), key, "~" + string);		    	
					if (appendOp.get().booleanValue()) {
						success = true;
					}
				}
			} catch ( Exception e) {
				
			}
		} 
		if ( !success) {
			System.out.println("Failed to Write: "  + key + " :" + string);
    		throw new DataObjectException("CoucbaseWriteFailed.Inconsistent CAS");			
		}
	}
	
	public static void upsert( CouchbaseClient client, String key, DataObject dataObject, int ttl) throws DataObjectException  {
		dataObject.setDate("updated_at", new Date());
		client.set(key, ttl, dataObject.toStringEncoded());
	}
	
	public static long getListPageNumber( long count, int pageSize ) {
		long page_num = 0;
		if ( count % listPageSize == 0) {
			page_num = 	count / pageSize;
		} else {
			page_num = 	(count / pageSize) + 1;
		}
		return page_num;
	}
	
	public static long getListSize( CouchbaseClient client, String listKeyPrefix ) {
		return (Long)client.get(listKeyPrefix + "_header");
	}

	public static String getListPageKey( String listKeyPrefix, long pageNumber) {
		return listKeyPrefix + "_" + String.valueOf(pageNumber);
	}
	
	public static List<String> getListKeys( CouchbaseClient client, String listKeyPrefix ) {
		List<String> keys = new ArrayList<String>();
		long currentPageNumber = getListPageNumber(getListSize(client, listKeyPrefix), DataObjectCouchbase.listPageSize);
		for ( long i = 1; i <= currentPageNumber; i++) {
			keys.add(getListPageKey(listKeyPrefix, i));
		}
		return keys;
	}
	
	public static DataObject getListKeys( CouchbaseClient client, List<String> listKeyPrefixes ) {
		DataObject result = new DataObject();
		List<String> keys = new ArrayList<String>();
		for ( String listKeyPrefix : listKeyPrefixes ) {
			long currentPageNumber = getListPageNumber(getListSize(client, listKeyPrefix), DataObjectCouchbase.listPageSize);
			for ( long i = 1; i <= currentPageNumber; i++) {
				String pageKey = getListPageKey(listKeyPrefix, i);
				result.setString(pageKey, listKeyPrefix);
				keys.add(pageKey);
			}
		}
		result.setStringList("AllKeys", keys);
		return result;
	}

	public static void addToStringList(CouchbaseClient client, String listKeyPrefix, String keyToAdd, int ttl) {
		long list_count = client.incr(listKeyPrefix + "_header", 1, 1);
		long page_num = getListPageNumber(list_count, DataObjectCouchbase.listPageSize);
		String key = getListPageKey(listKeyPrefix, page_num);
		appendString( client, key, keyToAdd, ttl);
	}

	public static void addToDataObjectList(CouchbaseClient client, String listKeyPrefix, DataObject dataObject, int ttl) {
		addToStringList(client, listKeyPrefix, dataObject.toStringEncoded(), ttl);
	}

	public static List<String> getStringList( CouchbaseClient client, String listKeyPrefix ) {
		List<String> items = new ArrayList<String>();
		List<String> keys = getListKeys(client, listKeyPrefix);
		Map<String, Object> existing = client.getBulk(keys);
		for ( String key : existing.keySet() ) {
			String valueList = (String)existing.get(key);
			for ( String value :  valueList.split("~") ) {
				items.add(value);
			}
		}
		return items;
	}

	public static Map<String, List<String>> getStringList( CouchbaseClient client, List<String> allKeys ) {
		Map<String, List<String>> items = new HashMap<String, List<String>>();
		DataObject listKeys = getListKeys(client, allKeys);
		List<String> keys = listKeys.getStringList("AllKeys"); 
		Map<String, Object> existing = client.getBulk(keys);
		for ( String key : existing.keySet() ) {
			String valueList = (String)existing.get(key);
			String rootKey = listKeys.getString(key);
			List<String> itemList = items.get(rootKey);
			if ( itemList == null) {
				itemList = new ArrayList<String>();
			}
			for ( String value :  valueList.split("~") ) {
				itemList.add(value);
			}
		}
		return items;
	}
	
	public static List<DataObject> getDataObjectList( CouchbaseClient client, String listKeyPrefix ) {
		List<DataObject> items = new ArrayList<DataObject>();
		List<String> keys = getListKeys(client, listKeyPrefix);
		Map<String, Object> existing = client.getBulk(keys);
		for ( String key : existing.keySet() ) {
			String valueList = (String)existing.get(key);
			for ( String value :  valueList.split("~") ) {
				DataObject dataObject = new DataObject();
				dataObject.fromStringEncoded(value);
				items.add(dataObject);
			}
		}
		return items;
	}

	public static Map<String, List<DataObject>> getDataObjectList( CouchbaseClient client, List<String> allKeys ) {
		Map<String, List<DataObject>> items = new HashMap<String, List<DataObject>>();
		DataObject listKeys = getListKeys(client, allKeys);
		List<String> keys = listKeys.getStringList("AllKeys"); 
		Map<String, Object> existing = client.getBulk(keys);
		for ( String key : existing.keySet() ) {
			String valueList = (String)existing.get(key);
			String rootKey = listKeys.getString(key);
			List<DataObject> itemList = items.get(rootKey);
			if ( itemList == null) {
				itemList = new ArrayList<DataObject>();
			}
			for ( String value :  valueList.split("~") ) {
				DataObject dataObject = new DataObject();
				dataObject.fromStringEncoded(value);
				itemList.add(dataObject);
			}
		}
		return items;
	}
	
}
