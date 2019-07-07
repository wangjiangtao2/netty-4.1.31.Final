/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.resolver.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRecordDecoder;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.ThrowableUtil;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static io.netty.resolver.dns.DnsAddressDecoder.decodeAddress;
import static java.lang.Math.min;

abstract class DnsResolveContext<T> {

    private static final FutureListener<AddressedEnvelope<DnsResponse, InetSocketAddress>> RELEASE_RESPONSE =
            new FutureListener<AddressedEnvelope<DnsResponse, InetSocketAddress>>() {
                @Override
                public void operationComplete(Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> future) {
                    if (future.isSuccess()) {
                        future.getNow().release();
                    }
                }
            };
    private static final RuntimeException NXDOMAIN_QUERY_FAILED_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new RuntimeException("No answer found and NXDOMAIN response code returned"),
            DnsResolveContext.class,
            "onResponse(..)");
    private static final RuntimeException CNAME_NOT_FOUND_QUERY_FAILED_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new RuntimeException("No matching CNAME record found"),
            DnsResolveContext.class,
            "onResponseCNAME(..)");
    private static final RuntimeException NO_MATCHING_RECORD_QUERY_FAILED_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new RuntimeException("No matching record type found"),
            DnsResolveContext.class,
            "onResponseAorAAAA(..)");
    private static final RuntimeException UNRECOGNIZED_TYPE_QUERY_FAILED_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new RuntimeException("Response type was unrecognized"),
            DnsResolveContext.class,
            "onResponse(..)");
    private static final RuntimeException NAME_SERVERS_EXHAUSTED_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new RuntimeException("No name servers returned an answer"),
            DnsResolveContext.class,
            "tryToFinishResolve(..)");

    final DnsNameResolver parent;
    private final DnsServerAddressStream nameServerAddrs;
    private final String hostname;
    private final int dnsClass;
    private final DnsRecordType[] expectedTypes;
    private final int maxAllowedQueries;
    final DnsRecord[] additionals;

    private final Set<Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>> queriesInProgress =
            Collections.newSetFromMap(
                    new IdentityHashMap<Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>, Boolean>());

    private List<T> finalResult;
    private int allowedQueries;
    private boolean triedCNAME;

    DnsResolveContext(DnsNameResolver parent,
                      String hostname, int dnsClass, DnsRecordType[] expectedTypes,
                      DnsRecord[] additionals, DnsServerAddressStream nameServerAddrs) {

        assert expectedTypes.length > 0;

        this.parent = parent;
        this.hostname = hostname;
        this.dnsClass = dnsClass;
        this.expectedTypes = expectedTypes;
        this.additionals = additionals;

        this.nameServerAddrs = ObjectUtil.checkNotNull(nameServerAddrs, "nameServerAddrs");
        maxAllowedQueries = parent.maxQueriesPerResolve();
        allowedQueries = maxAllowedQueries;
    }

    /**
     * The {@link DnsCache} to use while resolving.
     */
    DnsCache resolveCache() {
        return parent.resolveCache();
    }

    /**
     * The {@link DnsCnameCache} that is used for resolving.
     */
    DnsCnameCache cnameCache() {
        return parent.cnameCache();
    }

    /**
     * The {@link AuthoritativeDnsServerCache} to use while resolving.
     */
    AuthoritativeDnsServerCache authoritativeDnsServerCache() {
        return parent.authoritativeDnsServerCache();
    }

    /**
     * Creates a new context with the given parameters.
     */
    abstract DnsResolveContext<T> newResolverContext(DnsNameResolver parent, String hostname,
                                                     int dnsClass, DnsRecordType[] expectedTypes,
                                                     DnsRecord[] additionals,
                                                     DnsServerAddressStream nameServerAddrs);

    /**
     * Converts the given {@link DnsRecord} into {@code T}.
     */
    abstract T convertRecord(DnsRecord record, String hostname, DnsRecord[] additionals, EventLoop eventLoop);

    /**
     * Returns a filtered list of results which should be the final result of DNS resolution. This must take into
     * account JDK semantics such as {@link NetUtil#isIpV6AddressesPreferred()}.
     */
    abstract List<T> filterResults(List<T> unfiltered);

    /**
     * Caches a successful resolution.
     */
    abstract void cache(String hostname, DnsRecord[] additionals,
                        DnsRecord result, T convertedResult);

    /**
     * Caches a failed resolution.
     */
    abstract void cache(String hostname, DnsRecord[] additionals,
                        UnknownHostException cause);

    void resolve(final Promise<List<T>> promise) {
        final String[] searchDomains = parent.searchDomains();
        if (searchDomains.length == 0 || parent.ndots() == 0 || StringUtil.endsWith(hostname, '.')) {
            internalResolve(hostname, promise);
        } else {
            final boolean startWithoutSearchDomain = hasNDots();
            final String initialHostname = startWithoutSearchDomain ? hostname : hostname + '.' + searchDomains[0];
            final int initialSearchDomainIdx = startWithoutSearchDomain ? 0 : 1;

            final Promise<List<T>> searchDomainPromise = parent.executor().newPromise();
            searchDomainPromise.addListener(new FutureListener<List<T>>() {
                private int searchDomainIdx = initialSearchDomainIdx;
                @Override
                public void operationComplete(Future<List<T>> future) {
                    Throwable cause = future.cause();
                    if (cause == null) {
                        promise.trySuccess(future.getNow());
                    } else {
                        if (DnsNameResolver.isTransportOrTimeoutError(cause)) {
                            promise.tryFailure(new SearchDomainUnknownHostException(cause, hostname));
                        } else if (searchDomainIdx < searchDomains.length) {
                            Promise<List<T>> newPromise = parent.executor().newPromise();
                            newPromise.addListener(this);
                            doSearchDomainQuery(hostname + '.' + searchDomains[searchDomainIdx++], newPromise);
                        } else if (!startWithoutSearchDomain) {
                            internalResolve(hostname, promise);
                        } else {
                            promise.tryFailure(new SearchDomainUnknownHostException(cause, hostname));
                        }
                    }
                }
            });
            doSearchDomainQuery(initialHostname, searchDomainPromise);
        }
    }

    private boolean hasNDots() {
        for (int idx = hostname.length() - 1, dots = 0; idx >= 0; idx--) {
            if (hostname.charAt(idx) == '.' && ++dots >= parent.ndots()) {
                return true;
            }
        }
        return false;
    }

    private static final class SearchDomainUnknownHostException extends UnknownHostException {
        private static final long serialVersionUID = -8573510133644997085L;

        SearchDomainUnknownHostException(Throwable cause, String originalHostname) {
            super("Search domain query failed. Original hostname: '" + originalHostname + "' " + cause.getMessage());
            setStackTrace(cause.getStackTrace());

            // Preserve the cause
            initCause(cause.getCause());
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    void doSearchDomainQuery(String hostname, Promise<List<T>> nextPromise) {
        DnsResolveContext<T> nextContext = newResolverContext(parent, hostname, dnsClass, expectedTypes,
                                                              additionals, nameServerAddrs);
        nextContext.internalResolve(hostname, nextPromise);
    }

    private static String hostnameWithDot(String name) {
        if (StringUtil.endsWith(name, '.')) {
            return name;
        }
        return name + '.';
    }

    private void internalResolve(String name, Promise<List<T>> promise) {
        for (;;) {
            // Resolve from cnameCache() until there is no more cname entry cached.
            String mapping = cnameCache().get(hostnameWithDot(name));
            if (mapping == null) {
                break;
            }
            name = mapping;
        }

        DnsServerAddressStream nameServerAddressStream = getNameServers(name);

        final int end = expectedTypes.length - 1;
        for (int i = 0; i < end; ++i) {
            if (!query(name, expectedTypes[i], nameServerAddressStream.duplicate(), promise)) {
                return;
            }
        }
        query(name, expectedTypes[end], nameServerAddressStream, promise);
    }

    /**
     * Returns the {@link DnsServerAddressStream} that was cached for the given hostname or {@code null} if non
     *  could be found.
     */
    private DnsServerAddressStream getNameServersFromCache(String hostname) {
        int len = hostname.length();

        if (len == 0) {
            // We never cache for root servers.
            return null;
        }

        // We always store in the cache with a trailing '.'.
        if (hostname.charAt(len - 1) != '.') {
            hostname += ".";
        }

        int idx = hostname.indexOf('.');
        if (idx == hostname.length() - 1) {
            // We are not interested in handling '.' as we should never serve the root servers from cache.
            return null;
        }

        // We start from the closed match and then move down.
        for (;;) {
            // Skip '.' as well.
            hostname = hostname.substring(idx + 1);

            int idx2 = hostname.indexOf('.');
            if (idx2 <= 0 || idx2 == hostname.length() - 1) {
                // We are not interested in handling '.TLD.' as we should never serve the root servers from cache.
                return null;
            }
            idx = idx2;

            DnsServerAddressStream entries = authoritativeDnsServerCache().get(hostname);
            if (entries != null) {
                // The returned List may contain unresolved InetSocketAddress instances that will be
                // resolved on the fly in query(....).
                return entries;
            }
        }
    }

    private void query(final DnsServerAddressStream nameServerAddrStream, final int nameServerAddrStreamIndex,
                       final DnsQuestion question,
                       final Promise<List<T>> promise, Throwable cause) {
        query(nameServerAddrStream, nameServerAddrStreamIndex, question,
                parent.dnsQueryLifecycleObserverFactory().newDnsQueryLifecycleObserver(question), promise, cause);
    }

    private void query(final DnsServerAddressStream nameServerAddrStream,
                       final int nameServerAddrStreamIndex,
                       final DnsQuestion question,
                       final DnsQueryLifecycleObserver queryLifecycleObserver,
                       final Promise<List<T>> promise,
                       final Throwable cause) {
        if (nameServerAddrStreamIndex >= nameServerAddrStream.size() || allowedQueries == 0 || promise.isCancelled()) {
            tryToFinishResolve(nameServerAddrStream, nameServerAddrStreamIndex, question, queryLifecycleObserver,
                               promise, cause);
            return;
        }

        --allowedQueries;

        final InetSocketAddress nameServerAddr = nameServerAddrStream.next();
        if (nameServerAddr.isUnresolved()) {
            queryUnresolvedNameserver(nameServerAddr, nameServerAddrStream, nameServerAddrStreamIndex, question,
                                      queryLifecycleObserver, promise, cause);
            return;
        }
        final ChannelPromise writePromise = parent.ch.newPromise();
        final Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> f = parent.query0(
                nameServerAddr, question, additionals, writePromise,
                parent.ch.eventLoop().<AddressedEnvelope<? extends DnsResponse, InetSocketAddress>>newPromise());
        queriesInProgress.add(f);

        queryLifecycleObserver.queryWritten(nameServerAddr, writePromise);

        f.addListener(new FutureListener<AddressedEnvelope<DnsResponse, InetSocketAddress>>() {
            @Override
            public void operationComplete(Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> future) {
                queriesInProgress.remove(future);

                if (promise.isDone() || future.isCancelled()) {
                    queryLifecycleObserver.queryCancelled(allowedQueries);

                    // Check if we need to release the envelope itself. If the query was cancelled the getNow() will
                    // return null as well as the Future will be failed with a CancellationException.
                    AddressedEnvelope<DnsResponse, InetSocketAddress> result = future.getNow();
                    if (result != null) {
                        result.release();
                    }
                    return;
                }

                final Throwable queryCause = future.cause();
                try {
                    if (queryCause == null) {
                        onResponse(nameServerAddrStream, nameServerAddrStreamIndex, question, future.getNow(),
                                   queryLifecycleObserver, promise);
                    } else {
                        // Server did not respond or I/O error occurred; try again.
                        queryLifecycleObserver.queryFailed(queryCause);
                        query(nameServerAddrStream, nameServerAddrStreamIndex + 1, question, promise, queryCause);
                    }
                } finally {
                    tryToFinishResolve(nameServerAddrStream, nameServerAddrStreamIndex, question,
                                       // queryLifecycleObserver has already been terminated at this point so we must
                                       // not allow it to be terminated again by tryToFinishResolve.
                                       NoopDnsQueryLifecycleObserver.INSTANCE,
                                       promise, queryCause);
                }
            }
        });
    }

    private void queryUnresolvedNameserver(final InetSocketAddress nameServerAddr,
                                           final DnsServerAddressStream nameServerAddrStream,
                                           final int nameServerAddrStreamIndex,
                                           final DnsQuestion question,
                                           final DnsQueryLifecycleObserver queryLifecycleObserver,
                                           final Promise<List<T>> promise,
                                           final Throwable cause) {
        final String nameServerName = PlatformDependent.javaVersion() >= 7 ?
                nameServerAddr.getHostString() : nameServerAddr.getHostName();
        assert nameServerName != null;

        // Placeholder so we will not try to finish the original query yet.
        final Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> resolveFuture = parent.executor()
                .newSucceededFuture(null);
        queriesInProgress.add(resolveFuture);

        Promise<List<InetAddress>> resolverPromise = parent.executor().newPromise();
        resolverPromise.addListener(new FutureListener<List<InetAddress>>() {
            @Override
            public void operationComplete(final Future<List<InetAddress>> future) {
                // Remove placeholder.
                queriesInProgress.remove(resolveFuture);

                if (future.isSuccess()) {
                    List<InetAddress> resolvedAddresses = future.getNow();
                    DnsServerAddressStream addressStream = new CombinedDnsServerAddressStream(
                            nameServerAddr, resolvedAddresses, nameServerAddrStream);
                    query(addressStream, nameServerAddrStreamIndex, question,
                          queryLifecycleObserver, promise, cause);
                } else {
                    // Ignore the server and try the next one...
                    query(nameServerAddrStream, nameServerAddrStreamIndex + 1,
                          question, queryLifecycleObserver, promise, cause);
                }
            }
        });
        if (!DnsNameResolver.doResolveAllCached(nameServerName, additionals, resolverPromise, resolveCache(),
                parent.resolvedInternetProtocolFamiliesUnsafe())) {
            final AuthoritativeDnsServerCache authoritativeDnsServerCache = authoritativeDnsServerCache();
            new DnsAddressResolveContext(parent, nameServerName, additionals,
                                         parent.newNameServerAddressStream(nameServerName),
                                         resolveCache(), new AuthoritativeDnsServerCache() {
                @Override
                public DnsServerAddressStream get(String hostname) {
                    // To not risk falling into any loop, we will not use the cache while following redirects but only
                    // on the initial query.
                    return null;
                }

                @Override
                public void cache(String hostname, InetSocketAddress address, long originalTtl, EventLoop loop) {
                    authoritativeDnsServerCache.cache(hostname, address, originalTtl, loop);
                }

                @Override
                public void clear() {
                    authoritativeDnsServerCache.clear();
                }

                @Override
                public boolean clear(String hostname) {
                    return authoritativeDnsServerCache.clear(hostname);
                }
            }).resolve(resolverPromise);
        }
    }

    private void onResponse(final DnsServerAddressStream nameServerAddrStream, final int nameServerAddrStreamIndex,
                            final DnsQuestion question, AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
                            final DnsQueryLifecycleObserver queryLifecycleObserver,
                            Promise<List<T>> promise) {
        try {
            final DnsResponse res = envelope.content();
            final DnsResponseCode code = res.code();
            if (code == DnsResponseCode.NOERROR) {
                if (handleRedirect(question, envelope, queryLifecycleObserver, promise)) {
                    // Was a redirect so return here as everything else is handled in handleRedirect(...)
                    return;
                }
                final DnsRecordType type = question.type();

                if (type == DnsRecordType.CNAME) {
                    onResponseCNAME(question, buildAliasMap(envelope.content(), cnameCache(), parent.executor()),
                                    queryLifecycleObserver, promise);
                    return;
                }

                for (DnsRecordType expectedType : expectedTypes) {
                    if (type == expectedType) {
                        onExpectedResponse(question, envelope, queryLifecycleObserver, promise);
                        return;
                    }
                }

                queryLifecycleObserver.queryFailed(UNRECOGNIZED_TYPE_QUERY_FAILED_EXCEPTION);
                return;
            }

            // Retry with the next server if the server did not tell us that the domain does not exist.
            if (code != DnsResponseCode.NXDOMAIN) {
                query(nameServerAddrStream, nameServerAddrStreamIndex + 1, question,
                      queryLifecycleObserver.queryNoAnswer(code), promise, null);
            } else {
                queryLifecycleObserver.queryFailed(NXDOMAIN_QUERY_FAILED_EXCEPTION);
            }
        } finally {
            ReferenceCountUtil.safeRelease(envelope);
        }
    }

    /**
     * Handles a redirect answer if needed and returns {@code true} if a redirect query has been made.
     */
    private boolean handleRedirect(
            DnsQuestion question, AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
            final DnsQueryLifecycleObserver queryLifecycleObserver, Promise<List<T>> promise) {
        final DnsResponse res = envelope.content();

        // Check if we have answers, if not this may be an non authority NS and so redirects must be handled.
        if (res.count(DnsSection.ANSWER) == 0) {
            AuthoritativeNameServerList serverNames = extractAuthoritativeNameServers(question.name(), res);
            if (serverNames != null) {
                int additionalCount = res.count(DnsSection.ADDITIONAL);

                AuthoritativeDnsServerCache authoritativeDnsServerCache = authoritativeDnsServerCache();
                for (int i = 0; i < additionalCount; i++) {
                    final DnsRecord r = res.recordAt(DnsSection.ADDITIONAL, i);

                    if (r.type() == DnsRecordType.A && !parent.supportsARecords() ||
                        r.type() == DnsRecordType.AAAA && !parent.supportsAAAARecords()) {
                        continue;
                    }

                    // We may have multiple ADDITIONAL entries for the same nameserver name. For example one AAAA and
                    // one A record.
                    serverNames.handleWithAdditional(parent, r, authoritativeDnsServerCache);
                }

                // Process all unresolved nameservers as well.
                serverNames.handleWithoutAdditionals(parent, resolveCache(), authoritativeDnsServerCache);

                List<InetSocketAddress> addresses = serverNames.addressList();

                // Give the user the chance to sort or filter the used servers for the query.
                DnsServerAddressStream serverStream = parent.newRedirectDnsServerStream(
                        question.name(), addresses);

                if (serverStream != null) {
                    query(serverStream, 0, question,
                          queryLifecycleObserver.queryRedirected(new DnsAddressStreamList(serverStream)),
                          promise, null);
                    return true;
                }
            }
        }
        return false;
    }

    private static final class DnsAddressStreamList extends AbstractList<InetSocketAddress> {

        private final DnsServerAddressStream duplicate;
        private List<InetSocketAddress> addresses;

        DnsAddressStreamList(DnsServerAddressStream stream) {
            duplicate = stream.duplicate();
        }

        @Override
        public InetSocketAddress get(int index) {
            if (addresses == null) {
                DnsServerAddressStream stream = duplicate.duplicate();
                addresses = new ArrayList<InetSocketAddress>(size());
                for (int i = 0; i < stream.size(); i++) {
                    addresses.add(stream.next());
                }
            }
            return addresses.get(index);
        }

        @Override
        public int size() {
            return duplicate.size();
        }

        @Override
        public Iterator<InetSocketAddress> iterator() {
            return new Iterator<InetSocketAddress>() {
                private final DnsServerAddressStream stream = duplicate.duplicate();
                private int i;

                @Override
                public boolean hasNext() {
                    return i < stream.size();
                }

                @Override
                public InetSocketAddress next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    i++;
                    return stream.next();
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    /**
     * Returns the {@code {@link AuthoritativeNameServerList} which were included in {@link DnsSection#AUTHORITY}
     * or {@code null} if non are found.
     */
    private static AuthoritativeNameServerList extractAuthoritativeNameServers(String questionName, DnsResponse res) {
        int authorityCount = res.count(DnsSection.AUTHORITY);
        if (authorityCount == 0) {
            return null;
        }

        AuthoritativeNameServerList serverNames = new AuthoritativeNameServerList(questionName);
        for (int i = 0; i < authorityCount; i++) {
            serverNames.add(res.recordAt(DnsSection.AUTHORITY, i));
        }
        return serverNames.isEmpty() ? null : serverNames;
    }

    private void onExpectedResponse(
            DnsQuestion question, AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
            final DnsQueryLifecycleObserver queryLifecycleObserver, Promise<List<T>> promise) {

        // We often get a bunch of CNAMES as well when we asked for A/AAAA.
        final DnsResponse response = envelope.content();
        final Map<String, String> cnames = buildAliasMap(response, cnameCache(), parent.executor());
        final int answerCount = response.count(DnsSection.ANSWER);

        boolean found = false;
        for (int i = 0; i < answerCount; i ++) {
            final DnsRecord r = response.recordAt(DnsSection.ANSWER, i);
            final DnsRecordType type = r.type();
            boolean matches = false;
            for (DnsRecordType expectedType : expectedTypes) {
                if (type == expectedType) {
                    matches = true;
                    break;
                }
            }

            if (!matches) {
                continue;
            }

            final String questionName = question.name().toLowerCase(Locale.US);
            final String recordName = r.name().toLowerCase(Locale.US);

            // Make sure the record is for the questioned domain.
            if (!recordName.equals(questionName)) {
                // Even if the record's name is not exactly same, it might be an alias defined in the CNAME records.
                String resolved = questionName;
                do {
                    resolved = cnames.get(resolved);
                    if (recordName.equals(resolved)) {
                        break;
                    }
                } while (resolved != null);

                if (resolved == null) {
                    continue;
                }
            }

            final T converted = convertRecord(r, hostname, additionals, parent.executor());
            if (converted == null) {
                continue;
            }

            if (finalResult == null) {
                finalResult = new ArrayList<T>(8);
            }
            finalResult.add(converted);

            cache(hostname, additionals, r, converted);
            found = true;

            // Note that we do not break from the loop here, so we decode/cache all A/AAAA records.
        }

        if (cnames.isEmpty()) {
            if (found) {
                queryLifecycleObserver.querySucceed();
                return;
            }
            queryLifecycleObserver.queryFailed(NO_MATCHING_RECORD_QUERY_FAILED_EXCEPTION);
        } else {
            queryLifecycleObserver.querySucceed();
            // We also got a CNAME so we need to ensure we also query it.
            onResponseCNAME(question, cnames,
                    parent.dnsQueryLifecycleObserverFactory().newDnsQueryLifecycleObserver(question), promise);
        }
    }

    private void onResponseCNAME(
            DnsQuestion question, Map<String, String> cnames,
            final DnsQueryLifecycleObserver queryLifecycleObserver,
            Promise<List<T>> promise) {

        // Resolve the host name in the question into the real host name.
        String resolved = question.name().toLowerCase(Locale.US);
        boolean found = false;
        while (!cnames.isEmpty()) { // Do not attempt to call Map.remove() when the Map is empty
                                    // because it can be Collections.emptyMap()
                                    // whose remove() throws a UnsupportedOperationException.
            final String next = cnames.remove(resolved);
            if (next != null) {
                found = true;
                resolved = next;
            } else {
                break;
            }
        }

        if (found) {
            followCname(question, resolved, queryLifecycleObserver, promise);
        } else {
            queryLifecycleObserver.queryFailed(CNAME_NOT_FOUND_QUERY_FAILED_EXCEPTION);
        }
    }

    private static Map<String, String> buildAliasMap(DnsResponse response, DnsCnameCache cache, EventLoop loop) {
        final int answerCount = response.count(DnsSection.ANSWER);
        Map<String, String> cnames = null;
        for (int i = 0; i < answerCount; i ++) {
            final DnsRecord r = response.recordAt(DnsSection.ANSWER, i);
            final DnsRecordType type = r.type();
            if (type != DnsRecordType.CNAME) {
                continue;
            }

            if (!(r instanceof DnsRawRecord)) {
                continue;
            }

            final ByteBuf recordContent = ((ByteBufHolder) r).content();
            final String domainName = decodeDomainName(recordContent);
            if (domainName == null) {
                continue;
            }

            if (cnames == null) {
                cnames = new HashMap<String, String>(min(8, answerCount));
            }

            String name = r.name().toLowerCase(Locale.US);
            String mapping = domainName.toLowerCase(Locale.US);

            // Cache the CNAME as well.
            cache.cache(hostnameWithDot(name), hostnameWithDot(mapping), r.timeToLive(), loop);
            cnames.put(name, mapping);
        }

        return cnames != null? cnames : Collections.<String, String>emptyMap();
    }

    private void tryToFinishResolve(final DnsServerAddressStream nameServerAddrStream,
                                    final int nameServerAddrStreamIndex,
                                    final DnsQuestion question,
                                    final DnsQueryLifecycleObserver queryLifecycleObserver,
                                    final Promise<List<T>> promise,
                                    final Throwable cause) {

        // There are no queries left to try.
        if (!queriesInProgress.isEmpty()) {
            queryLifecycleObserver.queryCancelled(allowedQueries);

            // There are still some queries in process, we will try to notify once the next one finishes until
            // all are finished.
            return;
        }

        // There are no queries left to try.
        if (finalResult == null) {
            if (nameServerAddrStreamIndex < nameServerAddrStream.size()) {
                if (queryLifecycleObserver == NoopDnsQueryLifecycleObserver.INSTANCE) {
                    // If the queryLifecycleObserver has already been terminated we should create a new one for this
                    // fresh query.
                    query(nameServerAddrStream, nameServerAddrStreamIndex + 1, question, promise, cause);
                } else {
                    query(nameServerAddrStream, nameServerAddrStreamIndex + 1, question, queryLifecycleObserver,
                          promise, cause);
                }
                return;
            }

            queryLifecycleObserver.queryFailed(NAME_SERVERS_EXHAUSTED_EXCEPTION);

            // .. and we could not find any expected records.

            // If cause != null we know this was caused by a timeout / cancel / transport exception. In this case we
            // won't try to resolve the CNAME as we only should do this if we could not get the expected records
            // because they do not exist and the DNS server did probably signal it.
            if (cause == null && !triedCNAME) {
                // As the last resort, try to query CNAME, just in case the name server has it.
                triedCNAME = true;

                query(hostname, DnsRecordType.CNAME, getNameServers(hostname), promise);
                return;
            }
        } else {
            queryLifecycleObserver.queryCancelled(allowedQueries);
        }

        // We have at least one resolved record or tried CNAME as the last resort..
        finishResolve(promise, cause);
    }

    private void finishResolve(Promise<List<T>> promise, Throwable cause) {
        if (!queriesInProgress.isEmpty()) {
            // If there are queries in progress, we should cancel it because we already finished the resolution.
            for (Iterator<Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>> i = queriesInProgress.iterator();
                 i.hasNext();) {
                Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> f = i.next();
                i.remove();

                if (!f.cancel(false)) {
                    f.addListener(RELEASE_RESPONSE);
                }
            }
        }

        if (finalResult != null) {
            // Found at least one resolved record.
            DnsNameResolver.trySuccess(promise, filterResults(finalResult));
            return;
        }

        // No resolved address found.
        final int tries = maxAllowedQueries - allowedQueries;
        final StringBuilder buf = new StringBuilder(64);

        buf.append("failed to resolve '").append(hostname).append('\'');
        if (tries > 1) {
            if (tries < maxAllowedQueries) {
                buf.append(" after ")
                   .append(tries)
                   .append(" queries ");
            } else {
                buf.append(". Exceeded max queries per resolve ")
                .append(maxAllowedQueries)
                .append(' ');
            }
        }
        final UnknownHostException unknownHostException = new UnknownHostException(buf.toString());
        if (cause == null) {
            // Only cache if the failure was not because of an IO error / timeout that was caused by the query
            // itself.
            cache(hostname, additionals, unknownHostException);
        } else {
            unknownHostException.initCause(cause);
        }
        promise.tryFailure(unknownHostException);
    }

    static String decodeDomainName(ByteBuf in) {
        in.markReaderIndex();
        try {
            return DefaultDnsRecordDecoder.decodeName(in);
        } catch (CorruptedFrameException e) {
            // In this case we just return null.
            return null;
        } finally {
            in.resetReaderIndex();
        }
    }

    private DnsServerAddressStream getNameServers(String hostname) {
        DnsServerAddressStream stream = getNameServersFromCache(hostname);
        return stream == null ? nameServerAddrs.duplicate() : stream;
    }

    private void followCname(DnsQuestion question, String cname, DnsQueryLifecycleObserver queryLifecycleObserver,
                             Promise<List<T>> promise) {
        for (;;) {
            // Resolve from cnameCache() until there is no more cname entry cached.
            String mapping = cnameCache().get(hostnameWithDot(cname));
            if (mapping == null) {
                break;
            }
            cname = mapping;
        }

        DnsServerAddressStream stream = getNameServers(cname);

        final DnsQuestion cnameQuestion;
        try {
            cnameQuestion = new DefaultDnsQuestion(cname, question.type(), dnsClass);
        } catch (Throwable cause) {
            queryLifecycleObserver.queryFailed(cause);
            PlatformDependent.throwException(cause);
            return;
        }
        query(stream, 0, cnameQuestion, queryLifecycleObserver.queryCNAMEd(cnameQuestion), promise, null);
    }

    private boolean query(String hostname, DnsRecordType type, DnsServerAddressStream dnsServerAddressStream,
                          Promise<List<T>> promise) {
        final DnsQuestion question;
        try {
            question = new DefaultDnsQuestion(hostname, type, dnsClass);
        } catch (Throwable cause) {
            // Assume a single failure means that queries will succeed. If the hostname is invalid for one type
            // there is no case where it is known to be valid for another type.
            promise.tryFailure(new IllegalArgumentException("Unable to create DNS Question for: [" + hostname + ", " +
                    type + ']', cause));
            return false;
        }
        query(dnsServerAddressStream, 0, question, promise, null);
        return true;
    }

    private final class CombinedDnsServerAddressStream implements DnsServerAddressStream {
        private final InetSocketAddress replaced;
        private final DnsServerAddressStream originalStream;
        private final List<InetAddress> resolvedAddresses;
        private Iterator<InetAddress> resolved;

        CombinedDnsServerAddressStream(InetSocketAddress replaced, List<InetAddress> resolvedAddresses,
                                       DnsServerAddressStream originalStream) {
            this.replaced = replaced;
            this.resolvedAddresses = resolvedAddresses;
            this.originalStream = originalStream;
            resolved = resolvedAddresses.iterator();
        }

        @Override
        public InetSocketAddress next() {
            if (resolved.hasNext()) {
                return nextResolved0();
            }
            InetSocketAddress address = originalStream.next();
            if (address.equals(replaced)) {
                resolved = resolvedAddresses.iterator();
                return nextResolved0();
            }
            return address;
        }

        private InetSocketAddress nextResolved0() {
            return parent.newRedirectServerAddress(resolved.next());
        }

        @Override
        public int size() {
            return originalStream.size() + resolvedAddresses.size() - 1;
        }

        @Override
        public DnsServerAddressStream duplicate() {
            return new CombinedDnsServerAddressStream(replaced, resolvedAddresses, originalStream.duplicate());
        }
    }

    /**
     * Holds the closed DNS Servers for a domain.
     */
    private static final class AuthoritativeNameServerList {

        private final String questionName;

        // We not expect the linked-list to be very long so a double-linked-list is overkill.
        private AuthoritativeNameServer head;

        private int nameServerCount;

        AuthoritativeNameServerList(String questionName) {
            this.questionName = questionName.toLowerCase(Locale.US);
        }

        void add(DnsRecord r) {
            if (r.type() != DnsRecordType.NS || !(r instanceof DnsRawRecord)) {
                return;
            }

            // Only include servers that serve the correct domain.
            if (questionName.length() <  r.name().length()) {
                return;
            }

            String recordName = r.name().toLowerCase(Locale.US);

            int dots = 0;
            for (int a = recordName.length() - 1, b = questionName.length() - 1; a >= 0; a--, b--) {
                char c = recordName.charAt(a);
                if (questionName.charAt(b) != c) {
                    return;
                }
                if (c == '.') {
                    dots++;
                }
            }

            if (head != null && head.dots > dots) {
                // We already have a closer match so ignore this one, no need to parse the domainName etc.
                return;
            }

            final ByteBuf recordContent = ((ByteBufHolder) r).content();
            final String domainName = decodeDomainName(recordContent);
            if (domainName == null) {
                // Could not be parsed, ignore.
                return;
            }

            // We are only interested in preserving the nameservers which are the closest to our qName, so ensure
            // we drop servers that have a smaller dots count.
            if (head == null || head.dots < dots) {
                nameServerCount = 1;
                head = new AuthoritativeNameServer(dots, r.timeToLive(), recordName, domainName);
            } else if (head.dots == dots) {
                AuthoritativeNameServer serverName = head;
                while (serverName.next != null) {
                    serverName = serverName.next;
                }
                serverName.next = new AuthoritativeNameServer(dots, r.timeToLive(), recordName, domainName);
                nameServerCount++;
            }
        }

        void handleWithAdditional(
                DnsNameResolver parent, DnsRecord r, AuthoritativeDnsServerCache authoritativeCache) {
            // Just walk the linked-list and mark the entry as handled when matched.
            AuthoritativeNameServer serverName = head;

            String nsName = r.name();
            InetAddress resolved = decodeAddress(r, nsName, parent.isDecodeIdn());
            if (resolved == null) {
                // Could not parse the address, just ignore.
                return;
            }

            while (serverName != null) {
                if (serverName.nsName.equalsIgnoreCase(nsName)) {
                    if (serverName.address != null) {
                        // We received multiple ADDITIONAL records for the same name.
                        // Search for the last we insert before and then append a new one.
                        while (serverName.next != null && serverName.next.isCopy) {
                            serverName = serverName.next;
                        }
                        AuthoritativeNameServer server = new AuthoritativeNameServer(serverName);
                        server.next = serverName.next;
                        serverName.next = server;
                        serverName = server;

                        nameServerCount++;
                    }
                    // We should replace the TTL if needed with the one of the ADDITIONAL record so we use
                    // the smallest for caching.
                    serverName.update(parent.newRedirectServerAddress(resolved), r.timeToLive());

                    // Cache the server now.
                    cache(serverName, authoritativeCache, parent.executor());
                    return;
                }
                serverName = serverName.next;
            }
        }

        // Now handle all AuthoritativeNameServer for which we had no ADDITIONAL record
        void handleWithoutAdditionals(
                DnsNameResolver parent, DnsCache cache, AuthoritativeDnsServerCache authoritativeCache) {
            AuthoritativeNameServer serverName = head;

            while (serverName != null) {
                if (serverName.address == null) {
                    // These will be resolved on the fly if needed.
                    cacheUnresolved(serverName, authoritativeCache, parent.executor());

                    // Try to resolve via cache as we had no ADDITIONAL entry for the server.

                    List<? extends DnsCacheEntry> entries = cache.get(serverName.nsName, null);
                    if (entries != null && !entries.isEmpty()) {
                        InetAddress address = entries.get(0).address();

                        // If address is null we have a resolution failure cached so just use an unresolved address.
                        if (address != null) {
                            serverName.update(parent.newRedirectServerAddress(address));

                            for (int i = 1; i < entries.size(); i++) {
                                address = entries.get(i).address();

                                assert address != null :
                                        "Cache returned a cached failure, should never return anything else";

                                AuthoritativeNameServer server = new AuthoritativeNameServer(serverName);
                                server.next = serverName.next;
                                serverName.next = server;
                                serverName = server;
                                serverName.update(parent.newRedirectServerAddress(address));

                                nameServerCount++;
                            }
                        }
                    }
                }
                serverName = serverName.next;
            }
        }

        private static void cacheUnresolved(
                AuthoritativeNameServer server, AuthoritativeDnsServerCache authoritativeCache, EventLoop loop) {
            // We still want to cached the unresolved address
            server.address = InetSocketAddress.createUnresolved(
                    server.nsName, DefaultDnsServerAddressStreamProvider.DNS_PORT);

            // Cache the server now.
            cache(server, authoritativeCache, loop);
        }

        private static void cache(AuthoritativeNameServer server, AuthoritativeDnsServerCache cache, EventLoop loop) {
            // Cache NS record if not for a root server as we should never cache for root servers.
            if (!server.isRootServer()) {
                cache.cache(server.domainName, server.address, server.ttl, loop);
            }
        }

        /**
         * Returns {@code true} if empty, {@code false} otherwise.
         */
        boolean isEmpty() {
            return nameServerCount == 0;
        }

        /**
         * Creates a new {@link List} which holds the {@link InetSocketAddress}es.
         */
        List<InetSocketAddress> addressList() {
            List<InetSocketAddress> addressList = new ArrayList<InetSocketAddress>(nameServerCount);

            AuthoritativeNameServer server = head;
            while (server != null) {
                if (server.address != null) {
                    addressList.add(server.address);
                }
                server = server.next;
            }
            return addressList;
        }
    }

    private static final class AuthoritativeNameServer {
        private final int dots;
        private final String domainName;
        final boolean isCopy;
        final String nsName;

        private long ttl;
        private InetSocketAddress address;

        AuthoritativeNameServer next;

        AuthoritativeNameServer(int dots, long ttl, String domainName, String nsName) {
            this.dots = dots;
            this.ttl = ttl;
            this.nsName = nsName;
            this.domainName = domainName;
            isCopy = false;
        }

        AuthoritativeNameServer(AuthoritativeNameServer server) {
            dots = server.dots;
            ttl = server.ttl;
            nsName = server.nsName;
            domainName = server.domainName;
            isCopy = true;
        }

        /**
         * Returns {@code true} if its a root server.
         */
        boolean isRootServer() {
            return dots == 1;
        }

        /**
         * Update the server with the given address and TTL if needed.
         */
        void update(InetSocketAddress address, long ttl) {
            assert this.address == null || this.address.isUnresolved();
            this.address = address;
            this.ttl = min(ttl, ttl);
        }

        void update(InetSocketAddress address) {
            update(address, Long.MAX_VALUE);
        }
    }
}
