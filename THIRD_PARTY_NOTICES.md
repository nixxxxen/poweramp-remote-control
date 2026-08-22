# Third-party notices

Poweramp Remote is distributed under the MIT License except for the specifically identified
Poweramp API-derived portions below. The applications also include third-party libraries under
their respective licenses.

Release distributors should provide this file and `licenses/Apache-2.0.txt` with both APKs.

## Poweramp public Intent API

Portions of `app/src/main/java/dev/powerampremote/server/PowerampContract.java` are derived and
modified from
[`PowerampAPI.java`](https://github.com/maxmpz/powerampapi/blob/master/poweramp_api_lib/src/main/java/com/maxmpz/poweramp/player/PowerampAPI.java).
Those portions are covered by the following upstream terms, not by the repository's MIT License:

> Copyright (C) 2011-2026 Maksim Petrov
>
> Redistribution and use in source and binary forms, with or without modification, are permitted
> for the widgets, plugins, applications and other software which communicate with Poweramp
> application on Android platform.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS ``AS IS'' AND ANY EXPRESS OR
> IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
> FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE FOUNDATION OR CONTRIBUTORS
> BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
> (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
> OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
> CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
> THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Poweramp and Max MP do not sponsor or endorse Poweramp Remote.

## Apache License 2.0 components

The Server and Phone Client include the following direct runtime dependencies:

- AndroidX Activity 1.13.0;
- AndroidX Media3 Common and Session 1.10.1;
- JourneyApps ZXing Android Embedded 4.3.0;
- ZXing Core 3.5.4.

Their resolved runtime graphs also include AndroidX annotation, arch, collection, Compose runtime
annotation, concurrent, core, ExifInterface, interpolator, lifecycle, media, Media3 datasource and
database, NavigationEvent, ProfileInstaller, SavedState, Startup, Tracing, and
VersionedParcelable components; Kotlin standard library 2.1.20; kotlinx.coroutines 1.9.0; JetBrains
annotations 23.0.0; Guava 33.3.1-android and failureaccess 1.0.2; and JSpecify 1.0.0.

These components are licensed under the Apache License 2.0. The repository also distributes the
Apache-licensed Gradle wrapper. A complete copy of the license is in
[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).

Project license sources:

- [AndroidX and Media3](https://source.android.com/docs/setup/about/licenses)
- [JourneyApps ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded/blob/master/LICENSE)
- [ZXing](https://github.com/zxing/zxing/blob/master/LICENSE)
- [Kotlin](https://github.com/JetBrains/kotlin/blob/v2.1.20/license/LICENSE.txt)
- [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/LICENSE.txt)
- [Guava](https://github.com/google/guava/blob/v33.3.1/LICENSE)
- [JSpecify](https://github.com/jspecify/jspecify/blob/v1.0.0/LICENSE)
- [JetBrains annotations](https://github.com/JetBrains/java-annotations/blob/23.0.0/LICENSE.txt)
- [Gradle](https://github.com/gradle/gradle/blob/master/LICENSE)

### Notices carried by Kotlin standard library 2.1.20

Parts of Kotlin's collections are derived from GWT, Copyright 2007-2008 Google Inc. Parts of its
unsigned-number implementation are derived from Guava's `UnsignedLongs`, Copyright 2011 The Guava
Authors. Both are covered by the Apache License 2.0 above. The version-specific Kotlin attribution
manifest is available in the
[`Kotlin 2.1.20 license directory`](https://github.com/JetBrains/kotlin/blob/v2.1.20/license/README.md).

Kotlin time code includes work from ThreeTenBP under this BSD 3-Clause license:

> Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos. All rights reserved.
>
> Redistribution and use in source and binary forms, with or without modification, are permitted
> provided that the following conditions are met:
>
> - Redistributions of source code must retain the above copyright notice, this list of
>   conditions and the following disclaimer.
> - Redistributions in binary form must reproduce the above copyright notice, this list of
>   conditions and the following disclaimer in the documentation and/or other materials provided
>   with the distribution.
> - Neither the name of JSR-310 nor the names of its contributors may be used to endorse or promote
>   products derived from this software without specific prior written permission.
>
> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
> IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
> FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
> CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
> DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
> DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
> IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
> OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Kotlin's JVM math implementation includes code derived from Boost special math functions,
Copyright Eric Ford and Hubert Holin 2001, under these terms:

> Boost Software License - Version 1.0 - August 17th, 2003
>
> Permission is hereby granted, free of charge, to any person or organization obtaining a copy of
> the software and accompanying documentation covered by this license (the "Software") to use,
> reproduce, display, distribute, execute, and transmit the Software, and to prepare derivative
> works of the Software, and to permit third-parties to whom the Software is furnished to do so,
> all subject to the following:
>
> The copyright notices in the Software and this entire statement, including the above license
> grant, this restriction and the following disclaimer, must be included in all copies of the
> Software, in whole or in part, and all derivative works of the Software, unless such copies or
> derivative works are solely in the form of machine-executable object code generated by a source
> language processor.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
> NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, TITLE AND
> NON-INFRINGEMENT. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR ANYONE DISTRIBUTING THE SOFTWARE BE
> LIABLE FOR ANY DAMAGES OR OTHER LIABILITY, WHETHER IN CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Development and test-only dependencies

The following dependencies are used to build or test the project and are not bundled into the
runtime APKs:

- JUnit 4.13.2 — [Eclipse Public License 1.0](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt);
- Hamcrest Core 1.3 — [BSD 3-Clause](https://github.com/hamcrest/JavaHamcrest/blob/hamcrest-1.3/LICENSE.txt);
- JSON-java 20240303 — [public-domain dedication](https://github.com/stleary/JSON-java/blob/20240303/LICENSE);
- Android Gradle Plugin and Android SDK build tools — Android/Open Source Project license terms.

The presence of a project name or link above does not imply endorsement of Poweramp Remote.
