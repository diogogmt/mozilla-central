/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set ts=2 et sw=2 tw=80: */
/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Indexed Database.
 *
 * The Initial Developer of the Original Code is
 * The Mozilla Foundation.
 * Portions created by the Initial Developer are Copyright (C) 2010
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Ben Turner <bent.mozilla@gmail.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

#ifndef mozilla_dom_indexeddb_idbobjectstore_h__
#define mozilla_dom_indexeddb_idbobjectstore_h__

#include "mozilla/dom/indexedDB/IndexedDatabase.h"

#include "nsIIDBObjectStore.h"
#include "nsIIDBTransaction.h"

#include "nsCycleCollectionParticipant.h"

#include "mozilla/dom/indexedDB/IDBTransaction.h"

class nsIScriptContext;
class nsPIDOMWindow;

BEGIN_INDEXEDDB_NAMESPACE

class AsyncConnectionHelper;
class Key;

struct ObjectStoreInfo;
struct IndexInfo;
struct IndexUpdateInfo;
struct StructuredCloneReadInfo;
struct StructuredCloneWriteInfo;

class IDBObjectStore : public nsIIDBObjectStore
{
public:
  NS_DECL_CYCLE_COLLECTING_ISUPPORTS
  NS_DECL_NSIIDBOBJECTSTORE

  NS_DECL_CYCLE_COLLECTION_CLASS(IDBObjectStore)

  static already_AddRefed<IDBObjectStore>
  Create(IDBTransaction* aTransaction,
         ObjectStoreInfo* aInfo,
         nsIAtom* aDatabaseId);

  static bool
  IsValidKeyPath(JSContext* aCx, const nsAString& aKeyPath);

  static nsresult
  AppendIndexUpdateInfo(PRInt64 aIndexID,
                        const nsAString& aKeyPath,
                        const nsTArray<nsString>& aKeyPathArray,
                        bool aUnique,
                        bool aMultiEntry,
                        JSContext* aCx,
                        jsval aObject,
                        nsTArray<IndexUpdateInfo>& aUpdateInfoArray);

  static nsresult
  UpdateIndexes(IDBTransaction* aTransaction,
                PRInt64 aObjectStoreId,
                const Key& aObjectStoreKey,
                bool aOverwrite,
                PRInt64 aObjectDataId,
                const nsTArray<IndexUpdateInfo>& aUpdateInfoArray);

  static nsresult
  GetStructuredCloneReadInfoFromStatement(mozIStorageStatement* aStatement,
                                          PRUint32 aDataIndex,
                                          PRUint32 aFileIdsIndex,
                                          FileManager* aFileManager,
                                          StructuredCloneReadInfo& aInfo);

  static void
  ClearStructuredCloneBuffer(JSAutoStructuredCloneBuffer& aBuffer);

  static bool
  DeserializeValue(JSContext* aCx,
                   StructuredCloneReadInfo& aCloneReadInfo,
                   jsval* aValue);

  static bool
  SerializeValue(JSContext* aCx,
                 StructuredCloneWriteInfo& aCloneWriteInfo,
                 jsval aValue);

  static JSObject*
  StructuredCloneReadCallback(JSContext* aCx,
                              JSStructuredCloneReader* aReader,
                              uint32_t aTag,
                              uint32_t aData,
                              void* aClosure);
  static JSBool
  StructuredCloneWriteCallback(JSContext* aCx,
                               JSStructuredCloneWriter* aWriter,
                               JSObject* aObj,
                               void* aClosure);

  static nsresult
  ConvertFileIdsToArray(const nsAString& aFileIds,
                        nsTArray<PRInt64>& aResult);

  const nsString& Name() const
  {
    return mName;
  }

  bool IsAutoIncrement() const
  {
    return mAutoIncrement;
  }

  bool IsWriteAllowed() const
  {
    return mTransaction->IsWriteAllowed();
  }

  PRInt64 Id() const
  {
    NS_ASSERTION(mId != LL_MININT, "Don't ask for this yet!");
    return mId;
  }

  const nsString& KeyPath() const
  {
    return mKeyPath;
  }

  const bool HasKeyPath() const
  {
    return !mKeyPath.IsVoid() || !mKeyPathArray.IsEmpty();
  }

  bool UsesKeyPathArray() const
  {
    return !mKeyPathArray.IsEmpty();
  }
  
  const nsTArray<nsString>& KeyPathArray() const
  {
    return mKeyPathArray;
  }

  IDBTransaction* Transaction()
  {
    return mTransaction;
  }

  ObjectStoreInfo* Info()
  {
    return mInfo;
  }

protected:
  IDBObjectStore();
  ~IDBObjectStore();

  nsresult GetAddInfo(JSContext* aCx,
                      jsval aValue,
                      jsval aKeyVal,
                      StructuredCloneWriteInfo& aCloneWriteInfo,
                      Key& aKey,
                      nsTArray<IndexUpdateInfo>& aUpdateInfoArray);

  nsresult AddOrPut(const jsval& aValue,
                    const jsval& aKey,
                    JSContext* aCx,
                    PRUint8 aOptionalArgCount,
                    nsIIDBRequest** _retval,
                    bool aOverwrite);

private:
  nsRefPtr<IDBTransaction> mTransaction;

  PRInt64 mId;
  nsString mName;
  nsString mKeyPath;
  nsTArray<nsString> mKeyPathArray;
  bool mAutoIncrement;
  nsCOMPtr<nsIAtom> mDatabaseId;
  nsRefPtr<ObjectStoreInfo> mInfo;
  PRUint32 mStructuredCloneVersion;

  nsTArray<nsRefPtr<IDBIndex> > mCreatedIndexes;
};

END_INDEXEDDB_NAMESPACE

#endif // mozilla_dom_indexeddb_idbobjectstore_h__
