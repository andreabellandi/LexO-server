# Explanatory Combinatorial Dictionary services

The paths below are relative to the Jersey service root. Mutating operations
require the `Authorization` header; retrieval can omit it only when free-viewer
mode is enabled. IRI query parameters must be URL-encoded.

## Creation (`ECDCreation.java`)

| METHOD | ENDPOINT | FUNCTION | DESCRIPTION |
| --- | --- | --- | --- |
| **GET** | `/ecd/create/ECDictionary` | Create an EC dictionary | Creates an Explanatory Combinatorial Dictionary for `lang`. The request requires `prefix` and `baseIRI`, and optionally accepts `desiredID` and `author`. The namespace is validated before the new dictionary is returned as JSON. |
| **POST** | `/ecd/create/lexicalFunction` | Create a lexical-function instance | Creates a lexical-function relation from the JSON body (`source`, `target`, `lexicalFunction`, and `type`). The request requires `prefix` and `baseIRI`, optionally accepts `desiredID` and `author`, and returns the created relation as JSON. |
| **POST** | `/ecd/create/ECDEntry` | Create an ECD entry | Creates a dictionary entry from a JSON body containing `label`, `type`, `language`, and one or more `pos` values. The request requires `prefix` and `baseIRI`, optionally accepts `desiredID` and `author`, validates the entry type and language resources, and returns the created entry. |
| **POST** | `/ecd/create/ECDForm` | Create ECD forms | Creates a form for the entry identified by `ECDEntry`, using the JSON fields `label`, `type`, `language`, and `pos`. The request requires `prefix` and `baseIRI`, optionally accepts `desiredID` and `author`, validates form type and language, and returns the forms created for the selected parts of speech. |
| **GET** | `/ecd/create/ECDMeaning` | Create an ECD meaning | Creates the next ordered meaning for the dictionary entry `DictEntryID` and part of speech `pos`. The request requires `prefix` and `baseIRI`, optionally accepts `desiredID` and `author`, and returns the created meaning as JSON. |

## Retrieval (`ECDData.java`)

| METHOD | ENDPOINT | FUNCTION | DESCRIPTION |
| --- | --- | --- | --- |
| **GET** | `/ecd/data/ECDComponents` | List component elements | Returns the direct elements belonging to the ECD component identified by `id`. |
| **GET** | `/ecd/data/ECDEntrySemantics` | Get an entry's semantic tree | Returns the recursively built hierarchy of meanings and nested components for the dictionary entry identified by `id`. |
| **GET** | `/ecd/data/ECDEntryMorphology` | Get an entry's morphology | Returns the morphological forms associated with the dictionary entry identified by `id`. |
| **GET** | `/ecd/data/ECDEntry` | Get ECD entry details | Returns the dictionary entry identified by `id`. A missing entry produces HTTP 404. |
| **GET** | `/ecd/data/ECDictionaries` | List EC dictionaries | Returns all available Explanatory Combinatorial Dictionaries. |
| **GET** | `/ecd/data/ECDictionary` | Get EC dictionary details | Returns the dictionary identified by `id`. |
| **POST** | `/ecd/data/ECDEntries` | Search ECD entries | Returns a paginated result with `totalHits` and matching entries. The JSON filter supports `text`, `searchMode`, `pos`, `author`, `lang`, `status`, `offset`, and `limit`. |
| **GET** | `/ecd/data/ECDLexicaFunctions` | List lexical functions for a sense | Returns the lexical-function relations in which the lexical sense identified by `id` participates. |
| **GET** | `/ecd/data/ECDMeaning` | Get ECD meaning details | Returns the meaning (lexical sense) identified by `id`. |

## Deletion (`ECDDeletion.java`)

These legacy deletion operations are exposed as `GET` endpoints even though
they modify data.

| METHOD | ENDPOINT | FUNCTION | DESCRIPTION |
| --- | --- | --- | --- |
| **GET** | `/ecd/delete/lexicalFunction` | Delete a lexical-function relation | Deletes the lexical-function relation identified by `id` and returns the manager result as plain text. |
| **GET** | `/ecd/delete/ECDForm` | Delete an ECD form | Deletes the form identified by `id` and returns the manager result as plain text. |
| **GET** | `/ecd/delete/ECDEntry` | Delete an ECD entry | Deletes the entry identified by `id`. By default, an entry with components is rejected; `force=true` bypasses that emptiness check. |
| **GET** | `/ecd/delete/ECDEntryPoS` | Remove a part of speech from an entry | Removes `pos` from the entry identified by `id`. The operation is rejected if that part of speech is absent or its lexical entry still has forms or senses. |
| **GET** | `/ecd/delete/ECDictionary` | Delete an EC dictionary | Deletes the dictionary identified by `id` only when it contains no entries. |
| **GET** | `/ecd/delete/ECDMeaning` | Delete an ECD meaning | Deletes the resource identified by `idECDMeaning` after verifying that it is a lexical sense. |

## Update (`ECDUpdate.java`)

Update bodies use `relation` and `value`; entry, form, and meaning updates may
also use `oldPoS` when changing a part of speech.

| METHOD | ENDPOINT | FUNCTION | DESCRIPTION |
| --- | --- | --- | --- |
| **POST** | `/ecd/update/ECDEntry` | Update an ECD entry | Applies the JSON updater to the dictionary entry identified by `id`. The request requires `author`, verifies that the target is an ECD entry, and returns the manager result as plain text. |
| **POST** | `/ecd/update/ECDForm` | Update an ECD form | Applies the JSON updater to the form identified by `id`. The request requires `author`, verifies that the target is a form, and returns the manager result as plain text. |
| **POST** | `/ecd/update/ECDMeaning` | Update an ECD meaning | Applies the JSON updater to the meaning identified by `id`. The request requires `author`, verifies that the target is a lexical sense, and returns the manager result as plain text. |
| **POST** | `/ecd/update/ECDMeaningOrdering` | Update meaning ordering | Replaces the ordering metadata for meanings of the dictionary entry identified by `id`. The JSON body contains `meanings`, whose items supply `sense`, `romanNumber`, `arabicNumber`, and `letter`; the target must be a dictionary entry. |

## Declared placeholders

The following routes are declared in the service classes but currently return
`null`; they are not implemented API operations and must not be used by clients.

| METHOD | ENDPOINT | INTENDED FUNCTION | STATUS |
| --- | --- | --- | --- |
| **POST** | `/ecd/create/ECDGovPat` | Create a government pattern | Not implemented. |
| **POST** | `/ecd/create/ECDSemanticMapping` | Create a semantic mapping | Not implemented. |
| **GET** | `/ecd/data/ECDGovPat` | Get government-pattern details | Not implemented. |
| **GET** | `/ecd/data/ECDGovPats` | List an entry's government patterns | Not implemented. |
| **GET** | `/ecd/delete/ECDGovPatt` | Delete a government pattern | Not implemented. |
| **GET** | `/ecd/delete/ECDSemanticMapping` | Delete a semantic mapping | Not implemented. |
