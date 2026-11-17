# localization Specification

## Purpose
Serves everyone who meets SnapSync, from its store page to its screens. It promises that SnapSync speaks English and
German, that the app and both store pages always speak the same languages, and that whatever SnapSync shows follows
the reader's language where one of them matches, with English otherwise. It also says what deliberately stays
English. Which words SnapSync uses is not a contract; that it speaks the reader's language is.
Decision record: changes/archive/2026-11-17-localize-store-listings

## Requirements

### Requirement: SnapSync speaks English and German

SnapSync SHALL speak exactly two languages: English, the base, and German. The app SHALL show its screens in the
first of the phone's preferred languages that SnapSync speaks, or in English when it speaks none of them. Where the
phone offers a per-app language setting, that setting SHALL offer exactly SnapSync's languages, and the app SHALL
follow the language chosen there. The app SHALL offer no language choice of its own.

#### Scenario: A German phone
- **WHEN** someone whose phone is set to German opens SnapSync
- **THEN** every screen is in German

#### Scenario: A language SnapSync does not speak
- **WHEN** someone whose phone is set to French only opens SnapSync
- **THEN** every screen is in English

#### Scenario: A later preferred language matches
- **WHEN** someone whose preferred languages are French, then German opens SnapSync
- **THEN** every screen is in German

#### Scenario: The per-app language setting
- **WHEN** someone opens the phone's language setting for SnapSync
- **THEN** it offers English and German, and the app follows the one they choose

### Requirement: Text the phone shows for SnapSync is in the app's language

The words the phone shows on SnapSync's behalf outside its screens SHALL be in the same language as the app's
screens. These are the explanation in the photo-access request and SnapSync's notifications.

#### Scenario: The photo-access request on a German phone
- **WHEN** SnapSync asks for photo access on a phone set to German
- **THEN** the request explains in German why SnapSync needs the photos

#### Scenario: The sharing notification on a German phone
- **WHEN** SnapSync shows its notification while sharing photos on an Android phone set to German
- **THEN** the notification and its category are named in German

### Requirement: Dates follow the app's language and the phone's region

A date or time the app shows SHALL be worded in the language its screens are in, and ordered and clocked (day
before or after month, 12- or 24-hour clock) by the phone's region. A month name SHALL never be in a different
language from the words around it.

#### Scenario: An English app in a 24-hour region
- **WHEN** the app shows a date on a phone set to French in France
- **THEN** the screen is in English, the month is named in English, and times use the 24-hour clock

#### Scenario: A German app
- **WHEN** the app shows a date on a phone set to German
- **THEN** the month is named in German

### Requirement: The store pages speak the app's languages

SnapSync's pages on the App Store and on Google Play SHALL be offered in exactly the languages the app speaks, never
in a language the app lacks and never missing one it speaks. In each language the page's text, the headline over
each screenshot and the words in the opening graphic SHALL be in that language. A reader whose store shows a
language SnapSync does not speak SHALL see the English page. The app's name SHALL be the same in every language.

#### Scenario: A German store
- **WHEN** someone whose App Store or Google Play is in German opens SnapSync's page
- **THEN** the description, the screenshot headlines and the opening graphic are in German

#### Scenario: Another language
- **WHEN** someone whose store is in French opens SnapSync's page
- **THEN** they see the English page

#### Scenario: The name does not change
- **WHEN** someone compares SnapSync's English and German store pages
- **THEN** both name the app the same way

#### Scenario: A language is added
- **WHEN** a release adds a language to the app
- **THEN** both store pages offer it from that release on

### Requirement: Some surfaces stay English

These SHALL stay English in every language: the app screens inside the store screenshots (the headlines over them
follow the store's language), the store's notes on what is new in a release, the public web site, and the page an
invite link opens in a browser.

#### Scenario: Screenshots on the German page
- **WHEN** someone views SnapSync's German store page
- **THEN** the screenshots show the app's screens in English under German headlines

#### Scenario: What's new on the German page
- **WHEN** a release is published and someone views its notes on SnapSync's German store page
- **THEN** the notes are in English

#### Scenario: The web pages
- **WHEN** someone whose browser prefers German opens SnapSync's web site or an invite link's page
- **THEN** the page is in English
