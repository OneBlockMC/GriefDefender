/*
 * This file is part of GriefDefender, licensed under the MIT License (MIT).
 *
 * Copyright (c) bloodmc
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.griefdefender.provider;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.ImmutableSet;
import com.griefdefender.GDPlayerData;
import com.griefdefender.GriefDefenderPlugin;
import com.griefdefender.api.Tristate;
import com.griefdefender.api.claim.Claim;
import com.griefdefender.api.permission.Context;
import com.griefdefender.api.permission.ContextKeys;
import com.griefdefender.api.permission.PermissionResult;
import com.griefdefender.api.permission.ResultTypes;
import com.griefdefender.api.permission.flag.Flag;
import com.griefdefender.api.permission.option.Option;
import com.griefdefender.cache.PermissionHolderCache;
import com.griefdefender.claim.GDClaim;
import com.griefdefender.permission.GDPermissionHolder;
import com.griefdefender.permission.GDPermissionResult;
import com.griefdefender.permission.GDPermissionUser;
import com.griefdefender.registry.OptionRegistryModule;
import net.kyori.text.TextComponent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.ContextSet;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.context.MutableContextSet;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.PermissionHolder.Identifier;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.query.QueryMode;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.query.dataorder.DataQueryOrder;
import net.luckperms.api.query.dataorder.DataQueryOrderFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FilenameUtils;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.service.ProviderRegistration;

public class LuckPermsProvider implements PermissionProvider {

    private final Cache<String, Group> groupCache = Caffeine.newBuilder().expireAfterAccess(30, TimeUnit.MINUTES)
            .build();
    private final Cache<String, User> userCache = Caffeine.newBuilder().expireAfterAccess(30, TimeUnit.MINUTES)
            .build();

    public static Comparator<Set<Context>> CONTEXT_COMPARATOR = new Comparator<Set<Context>>() {
        @Override
        public int compare(Set<Context> s1, Set<Context> s2) {
            if (s1.size() > s2.size()) {
                return -1;
            }
            if (s1.size() < s2.size()) {
                return 1;
            }
            return s1.equals(s2) ? 0 : -1;
        }
    };

    private final LuckPerms luckPermsApi;
    private final static DefaultDataQueryOrderFunction DEFAULT_DATA_QUERY_ORDER = new DefaultDataQueryOrderFunction();

    public LuckPermsProvider() {
        final ProviderRegistration<LuckPerms> service = Sponge.getServiceManager().getRegistration(LuckPerms.class).orElse(null);
        this.luckPermsApi = service.getProvider();
    }

    public LuckPerms getApi() {
        return this.luckPermsApi;
    }

    @Override
    public String getServerName() {
        return this.luckPermsApi.getServerName();
    }

    @Override
    public boolean hasGroupSubject(String identifier) {
        return this.getGroupSubject(identifier) != null;
    }

    public PermissionHolder getLuckPermsHolder(GDPermissionHolder holder) {
        if (holder.getIdentifier().equalsIgnoreCase("default")) {
            return this.luckPermsApi.getGroupManager().getGroup("default");
        }
        if (holder instanceof GDPermissionUser) {
            return this.getLuckPermsUser(holder.getIdentifier());
        }

        return this.getLuckPermsGroup(holder.getIdentifier());
    }

    public User getLuckPermsUser(String identifier) {
        User user = this.userCache.getIfPresent(identifier);
        if (user != null) {
            return user;
        }

        UUID uuid = null;
        if (identifier.length() == 36) {
            try {
                uuid = UUID.fromString(identifier);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
        if (uuid != null) {
            user = this.getUserSubject(uuid);
        }

        if (user == null) {
            user = this.luckPermsApi.getUserManager().getUser(identifier);
        }
        if (user != null) {
            this.userCache.put(identifier, user);
        }

        return user;
    }

    public Group getLuckPermsGroup(String identifier) {
        if (identifier.equalsIgnoreCase("default")) {
            return this.luckPermsApi.getGroupManager().getGroup("default");
        }
        Group group = this.groupCache.getIfPresent(identifier);
        if (group != null) {
            return group;
        }

        group = this.luckPermsApi.getGroupManager().getGroup(identifier);
        if (group != null) {
            this.groupCache.put(identifier, group);
        }
        return group;
    }

    public Group getGroupSubject(String identifier) {
        final Group group = this.luckPermsApi.getGroupManager().getGroup(identifier);
        if (group != null) {
            return group;
        }

        try {
            return this.luckPermsApi.getGroupManager().loadGroup(identifier).get().orElse(null);
        } catch (InterruptedException e) {
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

   /* public GDPermissionUser getUserSubject(String name) {
        final User user = this.luckPermsApi.getUserManager().getUser(name);
        if (user != null) {
            return new GDPermissionUser(user);
        }

        try {
            final UUID uuid = this.luckPermsApi.getUserManager().lookupUuid(name).get();
            if (uuid != null) {
                return this.luckPermsApi.getUserManager().loadUser(uuid).get();
            }
            return null;
        } catch (InterruptedException e) {
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }*/

    public UUID lookupUserUniqueId(String name) {
        final User user = this.getLuckPermsUser(name);
        if (user != null) {
            return user.getUniqueId();
        }
        return null;
    }

    public User getUserSubject(UUID uuid) {
        User user = this.luckPermsApi.getUserManager().getUser(uuid);
        if (user != null) {
            return user;
        }

        try {
            user = this.luckPermsApi.getUserManager().loadUser(uuid).get();
            if (user != null) {
                return user;
            }
        } catch (InterruptedException e) {
            return null;
        } catch (ExecutionException e) {
            return null;
        }
        return null;
    }

    public List<String> getAllLoadedPlayerNames() {
        List<String> subjectList = new ArrayList<>();
        for (User user : this.luckPermsApi.getUserManager().getLoadedUsers()) {
            final String name = user.getUsername();
            if (name != null) {
                subjectList.add(name);
            }
        }
        if (!subjectList.contains("public")) {
            subjectList.add("public");
        }
        return subjectList;
    } 

    public List<String> getAllLoadedGroupNames() {
        List<String> subjectList = new ArrayList<>();
        for (Group group : this.luckPermsApi.getGroupManager().getLoadedGroups()) {
            final String name = group.getName();
            if (name != null) {
                subjectList.add(name);
            }
        }
        if (!subjectList.contains("public")) {
            subjectList.add("public");
        }
        return subjectList;
    } 

    public void addActiveContexts(Set<Context> contexts, GDPermissionHolder permissionHolder) {
        addActiveContexts(contexts, permissionHolder, null, null);
    }

    public void addActiveContexts(Set<Context> contexts, GDPermissionHolder permissionHolder, GDPlayerData playerData, Claim claim) {
        if (playerData != null) {
            playerData.ignoreActiveContexts = true;
        }
        final PermissionHolder luckPermsHolder = this.getLuckPermsHolder(permissionHolder);
        if (luckPermsHolder instanceof Group) {
            contexts.addAll(this.getGDContexts(this.luckPermsApi.getContextManager().getStaticContext().mutableCopy()));
            return;
        }

        ImmutableContextSet contextSet = this.luckPermsApi.getContextManager().getContext((User) luckPermsHolder).orElse(null);
        if (contextSet == null) {
            contextSet = this.luckPermsApi.getContextManager().getStaticContext();
        }
        if (contextSet == null) {
            return;
        }
        MutableContextSet activeContexts = contextSet.mutableCopy();
        if (playerData != null && claim != null) {
            final Claim parent = claim.getParent().orElse(null);
            if (parent != null && claim.getData() != null && claim.getData().doesInheritParent()) {
                activeContexts.remove(parent.getContext().getKey(), parent.getContext().getValue());
            } else {
                activeContexts.remove(claim.getContext().getKey(), claim.getContext().getValue());
            }
        }
        contexts.addAll(this.getGDContexts(activeContexts));
    }

    public void clearPermissions(GDClaim claim) {
        Map<Set<Context>, Map<String, Boolean>> permissionMap = this.getPermanentPermissions(GriefDefenderPlugin.DEFAULT_HOLDER);
        for (Entry<Set<Context>, Map<String, Boolean>> mapEntry : permissionMap.entrySet()) {
            for (Context context : mapEntry.getKey()) {
                if (context.getKey().equalsIgnoreCase("gd_claim") && context.getValue().equalsIgnoreCase(claim.getUniqueId().toString())) {
                    this.clearPermissions(GriefDefenderPlugin.DEFAULT_HOLDER, mapEntry.getKey());
                    break;
                }
            }
        }
    }

    public void clearPermissions(GDPermissionHolder holder, Context context) {
        clearPermissions(holder, ImmutableSet.of(context));
    }

    public void clearPermissions(GDPermissionHolder holder, Set<Context> contexts) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return;
        }

        ImmutableContextSet set = this.getLPContexts(contexts).immutableCopy();
        permissionHolder.data().clear(set);
        this.savePermissionHolder(permissionHolder);
    }

    public boolean holderHasPermission(GDPermissionHolder holder, String permission) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return false;
        }

        final QueryOptions query = QueryOptions.builder(QueryMode.CONTEXTUAL).option(DataQueryOrderFunction.KEY, DEFAULT_DATA_QUERY_ORDER).build();
        return permissionHolder.getCachedData().getPermissionData(query).checkPermission(permission).asBoolean();
    }

    public Map<String, Boolean> getPermissions(GDPermissionHolder holder, Set<Context> contexts) {
        ImmutableContextSet set = this.getLPContexts(contexts).immutableCopy();
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new HashMap<>();
        }

        final QueryOptions query = QueryOptions.builder(QueryMode.CONTEXTUAL).option(DataQueryOrderFunction.KEY, DEFAULT_DATA_QUERY_ORDER).context(set).build();
        CachedPermissionData cachedData = permissionHolder.getCachedData().getPermissionData(query);
        return cachedData.getPermissionMap();
    }

    public Map<String, String> getOptions(GDPermissionHolder holder, Set<Context> contexts) {
        ImmutableContextSet set = this.getLPContexts(contexts).immutableCopy();
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new HashMap<>();
        }

        final QueryOptions query = QueryOptions.builder(QueryMode.CONTEXTUAL).option(DataQueryOrderFunction.KEY, DEFAULT_DATA_QUERY_ORDER).context(set).build();
        CachedMetaData cachedData = permissionHolder.getCachedData().getMetaData(query);
        // TODO
        Map<String, String> metaMap = new HashMap<>();
        for (Map.Entry<String, List<String>> mapEntry : cachedData.getMeta().entrySet()) {
            metaMap.put(mapEntry.getKey(), mapEntry.getValue().get(0));
        }
        return metaMap;
    }

    public Map<Set<Context>, Map<String, Boolean>> getPermanentPermissions(GDPermissionHolder holder) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new HashMap<>();
        }

        final Collection<Node> nodes = permissionHolder.data().toCollection();
        Map<Set<Context>, Map<String, Boolean>> permanentPermissionMap = new TreeMap<Set<Context>, Map<String, Boolean>>(CONTEXT_COMPARATOR);
        for (Node node : nodes) {
            if (node.getType() != NodeType.PERMISSION) {
                continue;
            }

            final PermissionNode permissionNode = (PermissionNode) node;
            final Set<Context> contexts = getGPContexts(node.getContexts());
            Map<String, Boolean> permissionEntry = permanentPermissionMap.get(contexts);
            if (permissionEntry == null) {
                permissionEntry = new HashMap<>();
                permissionEntry.put(permissionNode.getPermission(), node.getValue());
                permanentPermissionMap.put(contexts, permissionEntry);
            } else {
                permissionEntry.put(permissionNode.getPermission(), node.getValue());
            }
        }

        return permanentPermissionMap;
    }

    public Map<Set<Context>, Map<String, Boolean>> getTransientPermissions(GDPermissionHolder holder) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new HashMap<>();
        }

        final Collection<Node> nodes = permissionHolder.transientData().toCollection();
        Map<Set<Context>, Map<String, Boolean>> transientPermissionMap = new TreeMap<Set<Context>, Map<String, Boolean>>(CONTEXT_COMPARATOR);
        for (Node node : nodes) {
            if (node.getType() != NodeType.PERMISSION) {
                continue;
            }

            final PermissionNode permissionNode = (PermissionNode) node;
            final Set<Context> contexts = getGPContexts(node.getContexts());
            Map<String, Boolean> permissionEntry = transientPermissionMap.get(contexts);
            if (permissionEntry == null) {
                permissionEntry = new HashMap<>();
                permissionEntry.put(permissionNode.getPermission(), node.getValue());
                transientPermissionMap.put(contexts, permissionEntry);
            } else {
                permissionEntry.put(permissionNode.getPermission(), node.getValue());
            }
        }
        return transientPermissionMap;
    }

    public Map<Set<Context>, Map<String, String>> getPermanentOptions(GDPermissionHolder holder) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new HashMap<>();
        }

        final Collection<Node> nodes = permissionHolder.data().toCollection();
        Map<Set<Context>, Map<String, String>> permanentPermissionMap = new TreeMap<Set<Context>, Map<String, String>>(CONTEXT_COMPARATOR);
        for (Node node : nodes) {
            if (node.getType() != NodeType.META) {
                continue;
            }

            final MetaNode metaNode = (MetaNode) node;
            final Set<Context> contexts = getGPContexts(node.getContexts());
            Map<String, String> metaEntry = permanentPermissionMap.get(contexts);
            if (metaEntry == null) {
                metaEntry = new HashMap<>();
                metaEntry.put(metaNode.getMetaKey(), metaNode.getMetaValue());
                permanentPermissionMap.put(contexts, metaEntry);
            } else {
                metaEntry.put(metaNode.getMetaKey(), metaNode.getMetaValue());
            }
        }
        return permanentPermissionMap;
    }

    public Map<Set<Context>, Map<String, String>> getTransientOptions(GDPermissionHolder holder) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new HashMap<>();
        }

        final Collection<Node> nodes = permissionHolder.transientData().toCollection();
        Map<Set<Context>, Map<String, String>> permanentPermissionMap = new TreeMap<Set<Context>, Map<String, String>>(CONTEXT_COMPARATOR);
        for (Node node : nodes) {
            if (node.getType() != NodeType.META) {
                continue;
            }

            final MetaNode metaNode = (MetaNode) node;
            final Set<Context> contexts = getGPContexts(node.getContexts());
            Map<String, String> metaEntry = permanentPermissionMap.get(contexts);
            if (metaEntry == null) {
                metaEntry = new HashMap<>();
                metaEntry.put(metaNode.getMetaKey(), metaNode.getMetaValue());
                permanentPermissionMap.put(contexts, metaEntry);
            } else {
                metaEntry.put(metaNode.getMetaKey(), metaNode.getMetaValue());
            }
        }
        return permanentPermissionMap;
    }

    public Map<String, String> getPermanentOptions(GDPermissionHolder holder, Set<Context> contexts) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new HashMap<>();
        }

        final Collection<Node> nodes = permissionHolder.data().toCollection();
        final Map<String, String> options = new HashMap<>();
        for (Node node : nodes) {
            if (node.getType() != NodeType.META) {
                continue;
            }

            final MetaNode metaNode = (MetaNode) node;
            if (contexts == null) {
                options.put(metaNode.getMetaKey(), metaNode.getMetaValue());
            } else if (getGPContexts(node.getContexts()).containsAll(contexts)) {
                options.put(metaNode.getMetaKey(), metaNode.getMetaValue());
            }
        }
        return options;
    }

    public Map<String, String> getTransientOptions(GDPermissionHolder holder, Set<Context> contexts) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new HashMap<>();
        }

        final Collection<Node> nodes = permissionHolder.transientData().toCollection();
        final Map<String, String> options = new HashMap<>();
        for (Node node : nodes) {
            if (node.getType() != NodeType.META) {
                continue;
            }

            final MetaNode metaNode = (MetaNode) node;
            if (contexts == null) {
                options.put(metaNode.getMetaKey(), metaNode.getMetaValue());
            } else if (getGPContexts(node.getContexts()).containsAll(contexts)) {
                options.put(metaNode.getMetaKey(), metaNode.getMetaValue());
            }
        }
        return options;
    }

    public Map<Set<Context>, Map<String, Boolean>> getAllPermissions(GDPermissionHolder holder) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new HashMap<>();
        }

        final Collection<Node> nodes = permissionHolder.getNodes();
        Map<Set<Context>, Map<String, Boolean>> permissionMap = new HashMap<>();
        Map<ContextSet, Set<Context>> contextMap = new HashMap<>();
        for (Node node : nodes) {
            Set<Context> contexts = null;
            if (contextMap.get(node.getContexts()) == null) {
                contexts = getGPContexts(node.getContexts());
                contextMap.put(node.getContexts(), contexts);
            } else {
                contexts = contextMap.get(node.getContexts());
            }
            Map<String, Boolean> permissionEntry = permissionMap.get(contexts);
            if (permissionEntry == null) {
                permissionEntry = new HashMap<>();
                permissionEntry.put(node.getKey(), node.getValue());
                permissionMap.put(contexts, permissionEntry);
            } else {
                permissionEntry.put(node.getKey(), node.getValue());
            }
        }
        return permissionMap;
    }

    public Set<Context> getGPContexts(ContextSet contextSet) {
        final Set<Context> gpContexts = new HashSet<>();
        for (net.luckperms.api.context.Context context : contextSet.toSet()) {
            if (context.getKey().startsWith("gd_") || context.getKey().equals("used_item") 
                    || context.getKey().equals("source") || context.getKey().equals("target")
                    || context.getKey().equals("world") || context.getKey().equals("server")
                    || context.getKey().equals("state")) {
                if (contextSet.containsKey(ContextKeys.CLAIM) && context.getKey().equals("server")) {
                    continue;
                }

                gpContexts.add(new Context(context.getKey(), context.getValue()));
            }
        }
        return gpContexts;
    }

    public Tristate getPermissionValue(GDPermissionHolder holder, String permission) {
        ImmutableContextSet set = ImmutableContextSet.empty();
        return this.getPermissionValue(holder, permission, set);
    }

    
    public Tristate getPermissionValue(GDClaim claim, GDPermissionHolder holder, String permission, MutableContextSet contexts) {
        return this.getPermissionValue(claim, holder, permission, this.getGDContexts(contexts));
    }

    public Tristate getPermissionValue(GDClaim claim, GDPermissionHolder holder, String permission, Set<Context> contexts) {
        return this.getPermissionValue(holder, permission, contexts);
    }

    public Tristate getPermissionValue(GDClaim claim, GDPermissionHolder holder, String permission, Set<Context> contexts, boolean checkTransient) {
        final Set<Context> activeContexts = new HashSet<>();
        this.addActiveContexts(activeContexts, holder, null, claim);
        contexts.addAll(activeContexts);
        final int contextHash =  Objects.hash(claim, holder, permission, contexts);
        final Cache<Integer, Tristate> cache = PermissionHolderCache.getInstance().getOrCreatePermissionCache(holder);
        Tristate result = cache.getIfPresent(contextHash);
        if (result != null) {
            return result;
        }
        // check persistent permissions first
        Map<Set<Context>, Map<String, Boolean>> permanentPermissions = getPermanentPermissions(holder);
        for (Entry<Set<Context>, Map<String, Boolean>> entry : permanentPermissions.entrySet()) {
            if (entry.getKey().isEmpty()) {
                continue;
            }
            boolean match = true;
            for (Context context : entry.getKey()) {
                if (!contexts.contains(context)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                for (Map.Entry<String, Boolean> permEntry : entry.getValue().entrySet()) {
                    if (FilenameUtils.wildcardMatch(permission, permEntry.getKey())) {
                        final Tristate value = Tristate.fromBoolean(permEntry.getValue());
                        cache.put(contextHash, value);
                        return value;
                    }
                }
                // If we get here, continue on normally
                continue;
            }
        }

        if (!checkTransient) {
            return Tristate.UNDEFINED;
        }

        // check transient permissions last
        Map<Set<Context>, Map<String, Boolean>> transientPermissions = getTransientPermissions(holder);
        for (Entry<Set<Context>, Map<String, Boolean>> entry : transientPermissions.entrySet()) {
            if (entry.getKey().isEmpty()) {
                continue;
            }
            boolean match = true;
            for (Context context : entry.getKey()) {
                if (!contexts.contains(context)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                for (Map.Entry<String, Boolean> permEntry : entry.getValue().entrySet()) {
                    if (FilenameUtils.wildcardMatch(permission, permEntry.getKey())) {
                        final Tristate value = Tristate.fromBoolean(permEntry.getValue());
                        cache.put(contextHash, value);
                        return value;
                    }
                }
                // If we get here, continue on normally
                continue;
            }
        }

        cache.put(contextHash, Tristate.UNDEFINED);
        return Tristate.UNDEFINED;
    }

    public Tristate getPermissionValueWithRequiredContexts(GDClaim claim, GDPermissionHolder holder, String permission, Set<Context> contexts, String contextFilter) {
        Map<Set<Context>, Map<String, Boolean>> permanentPermissions = getPermanentPermissions(holder);
        for (Entry<Set<Context>, Map<String, Boolean>> entry : permanentPermissions.entrySet()) {
            if (entry.getKey().isEmpty()) {
                continue;
            }
            boolean match = true;
            for (Context context : entry.getKey()) {
                if (!contexts.contains(context)) {
                    match = false;
                    break;
                }
            }

            // Check for required contexts
            for (Context context : contexts) {
                if (!context.getKey().contains(contextFilter) && !context.getKey().equalsIgnoreCase("world")) {
                    if (!entry.getKey().contains(context)) {
                        match = false;
                        break;
                    }
                }
            }
            if (match) {
                for (Map.Entry<String, Boolean> permEntry : entry.getValue().entrySet()) {
                    if (FilenameUtils.wildcardMatch(permission, permEntry.getKey())) {
                        final Tristate value = Tristate.fromBoolean(permEntry.getValue());
                        return value;
                    }
                }
            }
        }
        return Tristate.UNDEFINED;
    }

    public Tristate getPermissionValue(GDPermissionHolder holder, String permission, Set<Context> contexts) {
        ImmutableContextSet contextSet = this.getLPContexts(contexts).immutableCopy();
        return this.getPermissionValue(holder, permission, contextSet);
    }

    public Tristate getPermissionValue(GDPermissionHolder holder, String permission, ContextSet contexts) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return Tristate.UNDEFINED;
        }

        final QueryOptions query = QueryOptions.builder(QueryMode.CONTEXTUAL).option(DataQueryOrderFunction.KEY, DEFAULT_DATA_QUERY_ORDER).context(contexts).build();
        CachedPermissionData cachedData = permissionHolder.getCachedData().getPermissionData(query);
        return getGDTristate(cachedData.checkPermission(permission));
    }

    // To set options, pass "meta.option".
    @Override
    public String getOptionValue(GDPermissionHolder holder, Option option, Set<Context> contexts) {
        ImmutableContextSet set = this.getLPContexts(contexts).immutableCopy();
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return null;
        }

        final QueryOptions query = QueryOptions.builder(QueryMode.CONTEXTUAL).option(DataQueryOrderFunction.KEY, DEFAULT_DATA_QUERY_ORDER).context(set).build();
        CachedMetaData metaData = permissionHolder.getCachedData().getMetaData(query);
        return metaData.getMetaValue(option.getPermission());
    }

    @Override
    public List<String> getOptionValueList(GDPermissionHolder holder, Option option, Set<Context> contexts) {
        ImmutableContextSet set = this.getLPContexts(contexts).immutableCopy();
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return null;
        }

        final QueryOptions query = QueryOptions.builder(QueryMode.CONTEXTUAL).option(DataQueryOrderFunction.KEY, DEFAULT_DATA_QUERY_ORDER).context(set).build();
        CachedMetaData metaData = permissionHolder.getCachedData().getMetaData(query);
        List<String> list = metaData.getMeta().get(option.getPermission());
        if (list == null) {
            return new ArrayList<>();
        }
        return list;
    }

    public PermissionResult setOptionValue(GDPermissionHolder holder, String key, String value, Set<Context> contexts) {
        DataMutateResult result = null;
        ImmutableContextSet set = this.getLPContexts(contexts).immutableCopy();
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            new GDPermissionResult(ResultTypes.FAILURE);
        }

        final Option option = OptionRegistryModule.getInstance().getById(key).orElse(null);
        if (option == null) {
            new GDPermissionResult(ResultTypes.FAILURE);
        }

        final Node node = MetaNode.builder().key(key).value(value).context(set).build();
        if (!value.equalsIgnoreCase("undefined")) {
            if (!option.multiValued()) {
                this.clearMeta(permissionHolder, key, set);
            }
            result = permissionHolder.data().add(node);
        } else {
            this.clearMeta(permissionHolder, key, set);
            this.savePermissionHolder(permissionHolder);
            return new GDPermissionResult(ResultTypes.SUCCESS);
        }
        if (result != null) {
            if (result.wasSuccessful()) {
                this.savePermissionHolder(permissionHolder);
                return new GDPermissionResult(ResultTypes.SUCCESS, TextComponent.builder().append(result.name()).build());
            }
            return new GDPermissionResult(ResultTypes.FAILURE, TextComponent.builder().append(result.name()).build());
        }

        return new GDPermissionResult(ResultTypes.FAILURE);
    }

    public PermissionResult setPermissionValue(GDPermissionHolder holder, String permission, Tristate value, Set<Context> contexts, boolean save) {
        DataMutateResult result = null;
        ImmutableContextSet set = this.getLPContexts(contexts).immutableCopy();
        final Node node = this.luckPermsApi.getNodeBuilderRegistry().forPermission().permission(permission).value(value.asBoolean()).context(set).build();
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new GDPermissionResult(ResultTypes.FAILURE);
        }

        if (value == Tristate.UNDEFINED) {
            result = permissionHolder.data().remove(node);
        } else {
            result = permissionHolder.data().add(node);
        }

        if (result.wasSuccessful()) {
            if (permissionHolder instanceof Group) {
                // If a group is changed, we invalidate all cache
                PermissionHolderCache.getInstance().invalidateAllPermissionCache();
            } else {
                // We need to invalidate cache outside of LP listener so we can guarantee proper result returns
                PermissionHolderCache.getInstance().getOrCreatePermissionCache(holder).invalidateAll();
            }

            if (save) {
                this.savePermissionHolder(permissionHolder);
            }

            return new GDPermissionResult(ResultTypes.SUCCESS, TextComponent.builder().append(result.name()).build());
        }

        return new GDPermissionResult(ResultTypes.FAILURE, TextComponent.builder().append(result.name()).build());
    }

    public void setTransientOption(GDPermissionHolder holder, String permission, String value, Set<Context> contexts) {
        MutableContextSet contextSet = this.getLPContexts(contexts);
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return;
        }

        final Node node = this.luckPermsApi.getNodeBuilderRegistry().forMeta().key(permission).value(value).context(contextSet).build();
        permissionHolder.transientData().add(node);
    }

    public void setTransientPermission(GDPermissionHolder holder, String permission, Boolean value, Set<Context> contexts) {
        MutableContextSet contextSet = this.getLPContexts(contexts);
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return;
        }

        final PermissionNode node = this.luckPermsApi.getNodeBuilderRegistry().forPermission().permission(permission).value(value).context(contextSet).build();
        permissionHolder.transientData().add(node);
    }

    public void savePermissionHolder(PermissionHolder holder) {
        if (holder instanceof User) {
            this.luckPermsApi.getUserManager().saveUser((User) holder);
        } else {
            this.luckPermsApi.getGroupManager().saveGroup((Group) holder);
        }
    }

    public void refreshCachedData(GDPermissionHolder holder) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return;
        }
        permissionHolder.getCachedData().invalidate();
    }

    @Override
    public CompletableFuture<Void> save(GDPermissionHolder holder) {
        final PermissionHolder permissionHolder = this.getLuckPermsHolder(holder);
        if (permissionHolder == null) {
            return new CompletableFuture<>();
        }

        if (permissionHolder instanceof User) {
            return this.luckPermsApi.getUserManager().saveUser((User) permissionHolder);
        } else {
            return this.luckPermsApi.getGroupManager().saveGroup((Group) permissionHolder);
        }
    }

    public Set<Context> getGDContexts(ContextSet contexts) {
        final Set<Context> gdContexts = new HashSet<>();
        contexts.forEach(entry -> {
            gdContexts.add(new Context(entry.getKey(), entry.getValue()));
        });

        return gdContexts;
    }

    public MutableContextSet getLPContexts(Set<Context> contexts) {
        MutableContextSet lpContexts = MutableContextSet.create();
        contexts.forEach(entry -> {
            lpContexts.add(entry.getKey(), entry.getValue());
        });

        return lpContexts;
    }

    public Tristate getGDTristate(net.luckperms.api.util.Tristate state) {
        if (state == net.luckperms.api.util.Tristate.TRUE) {
            return Tristate.TRUE;
        }
        if (state == net.luckperms.api.util.Tristate.FALSE) {
            return Tristate.FALSE;
        }
        return Tristate.UNDEFINED;
    }

    private void clearMeta(PermissionHolder holder, String metaKey, ContextSet set) {
        holder.data().clear(set, NodeType.META.predicate(node -> node.getMetaKey().equals(metaKey)));
    }

    private static class DefaultDataQueryOrderFunction implements DataQueryOrderFunction {

        @Override
        public Comparator<DataType> getOrderComparator(Identifier identifier) {
            if (identifier.getType() == Identifier.GROUP_TYPE && identifier.getName().equalsIgnoreCase("default")) {
                return DataQueryOrder.TRANSIENT_LAST;
            }

            return DataQueryOrder.TRANSIENT_FIRST;
        }
    }
}