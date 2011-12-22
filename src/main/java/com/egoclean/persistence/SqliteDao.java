package com.egoclean.persistence;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

/**
 * This generic class provides some Sqlite logic that allows us to persist
 * and retrieve an object of any type. If you want to persist a bean into
 * the sqlite database, you must create a SqliteDao for that specific
 * class and implement the getValuesFromBean and getBeanFromCursor methods.
 */
class SqliteDao {
    private final SQLiteDatabase db;

    SqliteDao(SqliteDb database) {
        db = database.getWritableDatabase();
    }

    <T> List<T> findAll(Class<? extends T> clazz) {
        List<T> result = new ArrayList<T>();
        Cursor query = db.query(getTableName(clazz), null, null, null, null, null, null);
        if (query.moveToFirst()) {
            do {
                result.add(getBeanFromCursor(clazz, query, new Node(clazz)));
            } while (query.moveToNext());
        }
        query.close();
        return result;
    }

    <T> T findFirstWhere(Class<? extends T> clazz, T sample) {
        String where = null;
        if (sample != null) {
            where = SQLHelper.getWhere(sample);
        }
        Cursor query = db.query(getTableName(clazz), null, where, null, null, null, null, "1");
        if (query.moveToFirst()) {
            T bean = getBeanFromCursor(clazz, query, new Node(clazz));
            query.close();
            return bean;
        }
        query.close();
        return null;
    }

    <T> List<T> findAll(Class<? extends T> clazz, T where) {
        Cursor query = getCursorFindAllWhere(clazz, where);
        List<T> beans = new ArrayList<T>();
        if (query.moveToFirst()) {
            do {
                T bean = getBeanFromCursor(clazz, query, new Node(clazz));
                beans.add(bean);
            } while (query.moveToNext());
        }
        query.close();
        return beans;
    }

    <T> Cursor getCursorFindAllWhere(Class<? extends T> clazz, T sample) {
        return db.query(getTableName(clazz), null, SQLHelper.getWhere(sample), null, null, null, null, null);
    }

    <T> int update(T bean, T sample) {
        try {
            ContentValues values = getValuesFromBean(bean);
            return db.update(getTableName(bean.getClass()), values, SQLHelper.getWhere(sample), null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Error inserting: " + e.getMessage());
        }
    }

    <T> long insert(T bean) {
        return insert(bean, new Node(bean.getClass()));
    }

    <T> long insert(T bean, Node tree) {
        return insert(bean, tree, null);
    }

    <T> long insert(T bean, Node tree, ContentValues initialValues) {
        try {
            ContentValues values = getValuesFromBean(bean);
            if (initialValues != null) {
                values.putAll(initialValues);
            }
            long id = db.insert(getTableName(bean.getClass()), null, values);
            // TODO set the primary key to the bean
            // TODO there must be flexible enough to allow AUTOINCREMENT keys
            insertChildrenOf(bean, tree);
            return id;
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Error inserting: " + e.getMessage());
        }
    }

    <T> long delete(T sample) {
        return db.delete(getTableName(sample.getClass()), SQLHelper.getWhere(sample), null);
    }

    private <T> ContentValues getValuesFromBean(T bean) throws IllegalAccessException {
        ContentValues values = new ContentValues();
        Class theClass = bean.getClass();
        // get each field and put its value in a content values object
        Field[] fields = theClass.getDeclaredFields();
        for (Field field : fields) {
            String normalize = SqlUtils.normalize(field.getName());
            Class type = field.getType();
            field.setAccessible(true);

            if (type == int.class || type == Integer.class) {
                values.put(normalize, (Integer) field.get(bean));
            } else if (type == long.class || type == Long.class) {
                if (SQLHelper.ID.equals(field.getName()) && ((Long) field.get(bean)) == 0) {
                    // this means we are referring to a primary key that has not been set yet... so do not add it
                    continue;
                }
                values.put(normalize, (Long) field.get(bean));
            } else if (type == boolean.class || type == Boolean.class) {
                values.put(normalize, (Integer) field.get(bean));
            } else if (type == float.class || type == Float.class) {
                values.put(normalize, (Float) field.get(bean));
            } else if (type == double.class || type == Double.class) {
                values.put(normalize, (Double) field.get(bean));
            } else if (type != List.class) {
                values.put(normalize, (String) field.get(bean));
            }
        }
        return values;
    }

    private <T> void insertChildrenOf(T bean, Node tree) throws IllegalAccessException {// bodom
        Class<?> theClass = bean.getClass();
        Field[] fields = theClass.getDeclaredFields();
        List<Field> collectionFields = new ArrayList<Field>();
        for (Field field : fields) {
            if (field.getType() == List.class) {
                collectionFields.add(field);
                field.setAccessible(true);
            }
        }

        for (Field field : collectionFields) {
            ParameterizedType stringListType = (ParameterizedType) field.getGenericType();
            Class<?> collectionClass = (Class<?>) stringListType.getActualTypeArguments()[0];
            Node child = new Node(collectionClass);
            if (tree.addChild(child)) {
                switch (Persistence.getRelationship(theClass, collectionClass)) {
                    case MANY_TO_MANY: {
                        List list = (List) field.get(bean);
                        for (Object object : list) {
                            long insert = insert(object, tree);
                            // insert items in the joined table
                            try {
                                Field id = theClass.getDeclaredField(SQLHelper.ID);
                                id.setAccessible(true);
                                Long beanId = (Long) id.get(bean);

                                ContentValues joinValues = new ContentValues();
                                joinValues.put(getTableName(theClass) + "_id", beanId);
                                joinValues.put(getTableName(collectionClass) + "_id", insert);

                                db.insert(ManyToMany.getTableName(theClass.getSimpleName(), collectionClass.getSimpleName()),
                                        null, joinValues);
                            } catch (NoSuchFieldException e) {
                                e.printStackTrace();
                            }
                        }
                        break;
                    }
                    case HAS_MANY:
                        List list = (List) field.get(bean);
                        for (Object object : list) {
                            try {
                                // prepare the object by setting the foreign value
                                HasMany hasMany = Persistence.belongsTo(collectionClass);
                                Field primaryForeignKey = theClass.getDeclaredField(hasMany.getThrough());
                                primaryForeignKey.setAccessible(true);
                                Object foreignValue = primaryForeignKey.get(bean);

                                // insert items in the relation table
                                ContentValues relationValues = getValuesFromObject(foreignValue, hasMany.getForeignKey());
                                insert(object, tree, relationValues);// TODO insert or update?
                            } catch (NoSuchFieldException ignored) {
                            }
                        }
                        break;
                }
                tree.removeChild(child);
            }
        }
    }

    private ContentValues getValuesFromObject(Object object, String key) {
        if (object == null) {
            return null;
        }
        Class<?> type = object.getClass();
        ContentValues values = new ContentValues();
        if (type == int.class || type == Integer.class) {
            values.put(key, (Integer) object);
        } else if (type == long.class || type == Long.class) {
            values.put(key, (Long) object);
        } else if (type == boolean.class || type == Boolean.class) {
            values.put(key, (Integer) object);
        } else if (type == float.class || type == Float.class) {
            values.put(key, (Float) object);
        } else if (type == double.class || type == Double.class) {
            values.put(key, (Double) object);
        } else {
            values.put(key, object.toString());
        }

        return values;
    }

    <T> T getBeanFromCursor(Class<? extends T> theClass, Cursor query, Node tree) {
        T bean;
        try {
            Constructor<? extends T> constructor = theClass.getConstructor();
            bean = constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Could not initialize object of type " + theClass);
        }

        // get each field and put its value in a content values object
        Field[] fields = theClass.getDeclaredFields();
        for (Field field : fields) {
            // get the column index
            String normalize = SqlUtils.normalize(field.getName());
            int columnIndex = query.getColumnIndex(normalize);
            // get an object value depending on the type
            Class type = field.getType();
            Object value = null;
            if (columnIndex == -1 && type == List.class) {
                ParameterizedType stringListType = (ParameterizedType) field.getGenericType();
                Class<?> collectionClass = (Class<?>) stringListType.getActualTypeArguments()[0];
                Node node = new Node(collectionClass);
                if (tree.addChild(node)) {
                    switch (Persistence.getRelationship(theClass, collectionClass)) {
                        case MANY_TO_MANY: {
                            // build a query that uses the joining table and the joined object
                            long id = query.getLong(query.getColumnIndex(SQLHelper.ID));
                            String collectionTableName = getTableName(collectionClass);
                            String sql = "SELECT * FROM " + getTableName(collectionClass) +
                                    " WHERE " + SQLHelper.ID + " IN (SELECT " + collectionTableName + "_id FROM " +
                                    ManyToMany.getTableName(theClass.getSimpleName(), collectionClass.getSimpleName()) +
                                    " WHERE " + getTableName(theClass) + "_id = '" + id + "')";
                            // execute the query
                            Cursor join = db.rawQuery(sql, null);
                            // set the result to the current field
                            List listValue = new ArrayList();
                            if (join.moveToFirst()) {
                                do {
                                    Object beanFromCursor = getBeanFromCursor(collectionClass, join, tree);
                                    listValue.add(beanFromCursor);
                                } while (join.moveToNext());
                            }
                            value = listValue;
                        }
                        break;
                        case HAS_MANY:
                            // build a query that uses the joining table and the joined object
                            HasMany belongsTo = Persistence.belongsTo(collectionClass);
                            Class<?> containerClass = belongsTo.getClasses()[0];
                            Field throughField;
                            try {
                                throughField = containerClass.getDeclaredField(belongsTo.getThrough());
                            } catch (NoSuchFieldException e) {
                                break;
                            }
                            Object foreignValue = getValueFromCursor(throughField.getType(), belongsTo.getThrough(), query);
                            if (foreignValue != null) {
                                String sql = "SELECT * FROM " + getTableName(collectionClass) +
                                        " WHERE " + belongsTo.getForeignKey() + " = '" + foreignValue + "'";
                                // execute the query and set the result to the current field
                                Cursor join = db.rawQuery(sql, null);
                                List listValue = new ArrayList();
                                if (join.moveToFirst()) {
                                    do {
                                        Object beanFromCursor = getBeanFromCursor(collectionClass, join, tree);
                                        listValue.add(beanFromCursor);
                                    } while (join.moveToNext());
                                }
                                value = listValue;
                            }
                            break;
                    }
                    tree.removeChild(node);
                }
            } else {// do not process collections here
                value = getValueFromCursor(type, field.getName(), query);
            }
            try {
                if (value != null) {
                    field.setAccessible(true);
                    field.set(bean, value);
                }
            } catch (Exception e) {
                throw new RuntimeException(String.format("An error occurred setting value to '%s', (%s): %s%n", field, value, e.getMessage()));
            }
        }
        return bean;
    }

    private Object getValueFromCursor(Class<?> type, String name, Cursor query) {
        // get the column index
        String normalize = SqlUtils.normalize(name);
        int columnIndex = query.getColumnIndex(normalize);
        // get an object value depending on the type
        Object value = null;
        if (type == int.class || type == Integer.class) {
            value = query.getInt(columnIndex);
        } else if (type == long.class || type == Long.class) {
            value = query.getLong(columnIndex);
        } else if (type == boolean.class || type == Boolean.class) {
            value = query.getInt(columnIndex) == 1;
        } else if (type == float.class || type == Float.class || type == double.class || type == Double.class) {
            value = query.getFloat(columnIndex);
        } else if (type == String.class) {
            value = query.getString(columnIndex);
        }
        return value;
    }

    final <T> String getTableName(Class<? extends T> clazz) {
        return SqlUtils.normalize(clazz.getSimpleName());
    }
}
