/**
 * Copyright (c) 2010-2016 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.mpd.internal;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.quartz.impl.matchers.GroupMatcher.jobGroupEquals;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.bff.javampd.admin.Admin;
import org.bff.javampd.database.MusicDatabase;
import org.bff.javampd.monitor.StandAloneMonitor;
import org.bff.javampd.output.MPDOutput;
import org.bff.javampd.output.OutputChangeEvent;
import org.bff.javampd.output.OutputChangeListener;
import org.bff.javampd.player.Player;
import org.bff.javampd.player.PlayerBasicChangeEvent;
import org.bff.javampd.player.PlayerBasicChangeListener;
import org.bff.javampd.player.PlayerChangeEvent;
import org.bff.javampd.player.TrackPositionChangeEvent;
import org.bff.javampd.player.TrackPositionChangeListener;
import org.bff.javampd.player.VolumeChangeEvent;
import org.bff.javampd.player.VolumeChangeListener;
import org.bff.javampd.playlist.Playlist;
import org.bff.javampd.server.MPD;
import org.bff.javampd.server.MPDConnectionException;
import org.bff.javampd.song.MPDSong;
import org.openhab.binding.mpd.MpdBindingProvider;
import org.openhab.core.binding.AbstractBinding;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Binding which communicates with (one or many) MPDs. It registers as listener
 * of the MPDs to accomplish real bidirectional communication.
 *
 * @author Thomas.Eichstaedt-Engelen
 * @author Petr.Klus
 * @author Matthew Bowman
 *
 * @since 0.8.0
 */
public class MpdBinding extends AbstractBinding<MpdBindingProvider> implements ManagedService {

    private static final String MPD_SCHEDULER_GROUP = "MPD";

    private static final Logger logger = LoggerFactory.getLogger(MpdBinding.class);

    private Map<String, MpdPlayerConfig> playerConfigCache = new HashMap<String, MpdPlayerConfig>();

    /** RegEx to validate a mpdPlayer config <code>'^(.*?)\\.(host|port|password)$'</code> */
    private static final Pattern EXTRACT_PLAYER_CONFIG_PATTERN = Pattern.compile("^(.*?)\\.(host|port|password)$");

    /** The value by which the volume is changed by each INCREASE or DECREASE-Event */
    private static final int VOLUME_CHANGE_SIZE = 5;

    /** The connection timeout to wait for a MPD connection */
    private static final int CONNECTION_TIMEOUT = 5000;

    public MpdBinding() {
        playerConfigCache = new HashMap<String, MpdBinding.MpdPlayerConfig>();
    }

    @Override
    public void activate() {
        connectAllPlayersAndMonitors();
    }

    @Override
    public void deactivate() {
        disconnectPlayersAndMonitors();
    }

    /**
     * @{inheritDoc}
     */
    @Override
    public void internalReceiveCommand(String itemName, Command command) {

        MpdBindingProvider provider;
        String matchingPlayerCommand;
        Object params = new Object(); // nothing by default
        if (command instanceof PercentType) {
            // we have received volume adjustment request
            matchingPlayerCommand = "PERCENT";
            params = command;
        } else if (command instanceof DecimalType) {
            // we have received play song id request
            matchingPlayerCommand = "NUMBER";
            params = command;
        } else {
            matchingPlayerCommand = command.toString();
        }

        provider = findFirstMatchingBindingProvider(itemName, matchingPlayerCommand);

        if (provider == null) {
            logger.warn("cannot find matching binding provider [itemName={}, command={}]", itemName, command);
            return;
        }

        String playerCommand = provider.getPlayerCommand(itemName, matchingPlayerCommand);
        if (StringUtils.isNotBlank(playerCommand)) {
            String playerCommandParam = provider.getPlayerCommandParam(itemName, matchingPlayerCommand);
            if (playerCommandParam != null) {
                params = playerCommandParam;
            }
            executePlayerCommand(playerCommand, params);
        }

    }

    /**
     * Find the first matching {@link MpdBindingProvider} according to
     * <code>itemName</code> and <code>command</code>.
     *
     * @param itemName
     * @param command
     *
     * @return the matching binding provider or <code>null</code> if no binding
     *         provider could be found
     */
    private MpdBindingProvider findFirstMatchingBindingProvider(String itemName, String command) {
        MpdBindingProvider firstMatchingProvider = null;
        for (MpdBindingProvider provider : this.providers) {

            String playerCommand = provider.getPlayerCommand(itemName, command);
            if (playerCommand != null) {
                firstMatchingProvider = provider;
                break;
            }
        }
        return firstMatchingProvider;
    }

    /**
     * Executes the given <code>playerCommandLine</code> on the MPD. The
     * <code>playerCommandLine</code> is split into its properties
     * <code>playerId</code> and <code>playerCommand</code>.
     *
     * @param playerCommandLine the complete commandLine which gets splitted into
     *            its properties.
     */
    private void executePlayerCommand(String playerCommandLine, Object commandParams) {
        String[] commandParts = playerCommandLine.split(":");
        String playerId = commandParts[0];
        String playerCommand = commandParts[1];

        MPD daemon = findMPDInstance(playerId);
        if (daemon == null) {
            // we give that player another chance -> try to reconnect
            reconnect(playerId);
        }

        if (daemon != null) {
            PlayerCommandTypeMapping pCommand = null;
            try {
                pCommand = PlayerCommandTypeMapping.fromString(playerCommand);
                Player player = daemon.getPlayer();
                Playlist playlist = daemon.getPlaylist();
                MusicDatabase db = daemon.getMusicDatabase();

                switch (pCommand) {
                    case PAUSE:
                        player.pause();
                        break;
                    case PLAY:
                        player.play();
                        break;
                    case STOP:
                        player.stop();
                        break;
                    case VOLUME_INCREASE:
                        player.setVolume(player.getVolume() + VOLUME_CHANGE_SIZE);
                        break;
                    case VOLUME_DECREASE:
                        player.setVolume(player.getVolume() - VOLUME_CHANGE_SIZE);
                        break;
                    case NEXT:
                        player.playNext();
                        break;
                    case PREV:
                        player.playPrevious();
                        break;
                    case PLAYSONG:
                        logger.debug("Searching for Song {}", commandParams);
                        Collection<MPDSong> songs = db.getSongDatabase().findTitle((String) commandParams);

                        Iterator<MPDSong> it = songs.iterator();
                        if (it.hasNext() == true) {
                            MPDSong song = it.next();
                            logger.debug("Song found: {}", song.getFile());

                            playlist.clearPlaylist();
                            playlist.addSong(song);
                            player.play();
                        } else {
                            logger.debug("Song not found: {}", commandParams);
                        }

                        break;
                    case PLAYSONGID:
                        logger.debug("Play id {}", ((DecimalType) commandParams).intValue());

                        MPDSong song = new MPDSong("", "");
                        song.setId(((DecimalType) commandParams).intValue());
                        player.playSong(song);
                        break;
                    case ENABLE:
                    case DISABLE:
                        Integer outputId = Integer.valueOf((String) commandParams);
                        Admin admin = daemon.getAdmin();
                        MPDOutput output = new MPDOutput(outputId - 1); // internally mpd uses 0-based indexing
                        if (pCommand == PlayerCommandTypeMapping.ENABLE) {
                            admin.enableOutput(output);
                        } else {
                            admin.disableOutput(output);
                        }
                        break;
                    case VOLUME:
                        logger.debug("Volume adjustment received: '{}' '{}'", pCommand, commandParams);
                        player.setVolume(((PercentType) commandParams).intValue());
                        break;
                    case TRACKARTIST:
                        logger.warn("received unsupported command 'trackartist'");
                        break;
                    case TRACKINFO:
                        logger.warn("received unsupported command 'trackinfo'");
                        break;
                    default:
                        break;

                }
            } catch (Exception e) {
                logger.warn("unknown playerCommand '{}'", playerCommand);
            }
        } else {
            logger.warn("didn't find player configuration instance for playerId '{}'", playerId);
        }

        logger.info("executed commandLine '{}' for player '{}'", playerCommand, playerId);
    }

    private MPD findMPDInstance(String playerId) {
        MpdPlayerConfig playerConfig = playerConfigCache.get(playerId);
        if (playerConfig != null) {
            return playerConfig.instance;
        }
        return null;
    }

    /**
     * Implementation of {@link PlayerBasicChangeListener}. Posts the translated
     * type of the {@link PlayerChangeEvent} onto the internal event bus.
     * <code>PLAYER_STARTED</code>-Events are translated to <code>ON</code> whereas
     * <code>PLAYER_PAUSED</code> and <code>PLAYER_STOPPED</code>-Events are
     * translated to <code>OFF</code>.
     *
     * In this case, we use the play state change to trigger full state update, including
     * artist and song name.
     *
     * @param pbce the event which type is translated and posted onto the internal
     *            event bus
     */
    public void playerBasicChange(String playerId, PlayerBasicChangeEvent pbce) {

        // trigger track name update
        determineSongChange(playerId);

        // trigger our play state change
        determinePlayStateChange(playerId, pbce.getStatus());
    }

    HashMap<String, MPDSong> songInfoCache = new HashMap<String, MPDSong>();
    HashMap<String, PlayerBasicChangeEvent.Status> playerStatusCache = new HashMap<String, PlayerBasicChangeEvent.Status>();

    public void trackPositionChanged(String playerId, TrackPositionChangeEvent tpce) {

        // cache song name internally, we do not want to fire every time

        // update track name
        determineSongChange(playerId);
    }

    private void broadcastPlayerStateChange(String playerId, PlayerCommandTypeMapping reportTo,
            OnOffType reportStatus) {
        String[] itemNames = getItemNamesByPlayerAndPlayerCommand(playerId, reportTo);
        for (String itemName : itemNames) {
            if (StringUtils.isNotBlank(itemName)) {
                eventPublisher.postUpdate(itemName, reportStatus);
            }
        }
    }

    private void determinePlayStateChange(String playerId, PlayerBasicChangeEvent.Status ps) {
        MPD daemon = findMPDInstance(playerId);
        if (daemon == null) {
            // we give that player another chance -> try to reconnect
            reconnect(playerId);
        }
        if (daemon != null) {
            PlayerBasicChangeEvent.Status curPs = playerStatusCache.get(playerId);

            if (curPs == null || ps != curPs) {
                logger.debug("Play state of '{}' changed to '{}'", playerId, ps);
                playerStatusCache.put(playerId, ps);

                PlayerCommandTypeMapping reportTo = PlayerCommandTypeMapping.STOP;
                OnOffType reportStatus = OnOffType.OFF;
                // trigger song update
                switch (ps) {
                    case PLAYER_PAUSED:
                    case PLAYER_STOPPED:
                        // stopped
                        reportTo = PlayerCommandTypeMapping.STOP;
                        reportStatus = OnOffType.OFF;
                        break;

                    case PLAYER_STARTED:
                    case PLAYER_UNPAUSED:
                        // playing
                        reportTo = PlayerCommandTypeMapping.PLAY;
                        reportStatus = OnOffType.ON;
                        break;
                }
                broadcastPlayerStateChange(playerId, reportTo, reportStatus);
            } else {
                // nothing, same state
            }
        } else {
            logger.warn("didn't find player configuration instance for playerId '{}'", playerId);
        }
    }

    private void determineSongChange(String playerId) {
        MPD daemon = findMPDInstance(playerId);
        if (daemon == null) {
            // we give that player another chance -> try to reconnect
            reconnect(playerId);
        }
        if (daemon != null) {
            try {
                Player player = daemon.getPlayer();

                // get the song object here
                MPDSong curSong = player.getCurrentSong();

                MPDSong curSongCache = songInfoCache.get(playerId);
                if (!songsEqual(curSong, curSongCache)) {
                    // song is different (or not in cache), update cache
                    songInfoCache.put(playerId, curSong);
                    // action the song change&notification
                    songChanged(playerId, curSong);
                }
            } catch (Exception e) {
                logger.warn("Failed to communicate with '{}'", playerId);
            }
        } else {
            logger.warn("didn't find player configuration instance for playerId '{}'", playerId);
        }
    }

    private boolean songsEqual(MPDSong song1, MPDSong song2) {
        if (!StringUtils.equals(getTitle(song1), getTitle(song2))) {
            return false;
        }

        return StringUtils.equals(getArtist(song1), getArtist(song2));
    }

    private String getTitle(MPDSong song) {
        if (song == null || song.getTitle() == null) {
            return "";
        }

        return song.getTitle().toString();
    }

    private String getArtist(MPDSong song) {
        if (song == null || song.getArtistName() == null) {
            return "";
        }

        return song.getArtistName().toString();
    }

    private void songChanged(String playerId, MPDSong newSong) {
        String title = getTitle(newSong);
        logger.debug("Current song {}: {}", playerId, title);

        String[] itemNames = getItemNamesByPlayerAndPlayerCommand(playerId, PlayerCommandTypeMapping.TRACKINFO);
        // move to utilities?
        for (String itemName : itemNames) {
            if (StringUtils.isNotBlank(itemName)) {
                eventPublisher.postUpdate(itemName, new StringType(title));
                logger.debug("Updated title: {} {}", itemName, title);
            }
        }

        String artist = getArtist(newSong);
        itemNames = getItemNamesByPlayerAndPlayerCommand(playerId, PlayerCommandTypeMapping.TRACKARTIST);
        for (String itemName : itemNames) {
            if (StringUtils.isNotBlank(itemName)) {
                eventPublisher.postUpdate(itemName, new StringType(artist));
                logger.debug("Updated artist: {}, {}", itemName, artist);
            }
        }

        int songID = newSong.getId();
        itemNames = getItemNamesByPlayerAndPlayerCommand(playerId, PlayerCommandTypeMapping.PLAYSONGID);
        for (String itemName : itemNames) {
            if (StringUtils.isNotBlank(itemName)) {
                eventPublisher.postUpdate(itemName, new DecimalType(songID));
                logger.debug("Updated songid: {}, {}", itemName, songID);
            }
        }
    }

    /**
     * More advanced state detection - allows to detect track changes etc.
     * However, the underlying MPD library does not seem to support this well
     */
    public void playerChanged(PlayerChangeEvent pce) {

    }

    /**
     * Implementation of {@link VolumeChangeListener}. Posts the volume value
     * of the {@link VolumeChangeEvent} onto the internal event bus.
     *
     * @param vce the event which volume value is posted onto the internal event bus
     */
    public void volumeChanged(String playerId, VolumeChangeEvent vce) {
        logger.debug("Volume on {} changed to {}", playerId, vce.getVolume());
        if (vce.getVolume() >= 0 && vce.getVolume() <= 100) {
            String[] itemNames = getItemNamesByPlayerAndPlayerCommand(playerId, PlayerCommandTypeMapping.VOLUME);
            for (String itemName : itemNames) {
                if (StringUtils.isNotBlank(itemName)) {
                    eventPublisher.postUpdate(itemName, new PercentType(vce.getVolume()));
                }
            }
        } else {
            logger.warn("Volume on {} is invalid: {} - ignoring it.", playerId, vce.getVolume());
        }
    }

    /**
     * Handles MPD output change events.
     *
     * @param playerId the playerId which generated the <code>event</code>
     * @param event the {@link OutputChangeEvent} that occurred
     *
     * @since 1.6.0
     */
    private void outputChanged(String playerId, OutputChangeEvent event) {

        logger.debug("Output change event from player {}", playerId);

        MPD daemon = findMPDInstance(playerId);
        if (daemon != null) {
            for (MPDOutput output : daemon.getAdmin().getOutputs()) {
                PlayerCommandTypeMapping playerCommand = output.isEnabled() ? PlayerCommandTypeMapping.ENABLE
                        : PlayerCommandTypeMapping.DISABLE;
                String[] itemNames = getItemsByPlayerCommandAndOutput(playerId, playerCommand, output);
                for (String itemName : itemNames) {
                    eventPublisher.postUpdate(itemName, (State) playerCommand.type);
                }
            }
        }
    }

    private String[] getItemsByPlayerCommandAndOutput(String playerId, PlayerCommandTypeMapping playerCommand,
            MPDOutput output) {
        Set<String> itemNames = new HashSet<String>();
        int outputId = output.getId() + 1; // internally mpd uses 0-based indexes
        for (MpdBindingProvider provider : this.providers) {
            itemNames.addAll(
                    Arrays.asList(provider.getItemNamesByPlayerOutputCommand(playerId, playerCommand, outputId)));
        }
        return itemNames.toArray(new String[itemNames.size()]);
    }

    private String[] getItemNamesByPlayerAndPlayerCommand(String playerId, PlayerCommandTypeMapping playerCommand) {
        Set<String> itemNames = new HashSet<String>();
        for (MpdBindingProvider provider : this.providers) {
            itemNames.addAll(Arrays.asList(provider.getItemNamesByPlayerAndPlayerCommand(playerId, playerCommand)));
        }
        return itemNames.toArray(new String[itemNames.size()]);
    }

    protected void addBindingProvider(MpdBindingProvider bindingProvider) {
        super.addBindingProvider(bindingProvider);
    }

    protected void removeBindingProvider(MpdBindingProvider bindingProvider) {
        super.removeBindingProvider(bindingProvider);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void updated(Dictionary config) throws ConfigurationException {
        if (config != null) {
            disconnectPlayersAndMonitors();
            cancelScheduler();

            Enumeration keys = config.keys();
            while (keys.hasMoreElements()) {

                String key = (String) keys.nextElement();

                // the config-key enumeration contains additional keys that we
                // don't want to process here ...
                if ("service.pid".equals(key)) {
                    continue;
                }

                Matcher matcher = EXTRACT_PLAYER_CONFIG_PATTERN.matcher(key);
                if (!matcher.matches()) {
                    logger.debug("given mpd player-config-key '" + key
                            + "' does not follow the expected pattern '<playername>.<host|port>'");
                    continue;
                }

                matcher.reset();
                matcher.find();

                String playerId = matcher.group(1);

                MpdPlayerConfig playerConfig = playerConfigCache.get(playerId);
                if (playerConfig == null) {
                    playerConfig = new MpdPlayerConfig();
                    playerConfigCache.put(playerId, playerConfig);
                }

                String configKey = matcher.group(2);
                String value = (String) config.get(key);

                if ("host".equals(configKey)) {
                    playerConfig.host = value;
                } else if ("port".equals(configKey)) {
                    playerConfig.port = Integer.valueOf(value);
                } else if ("password".equals(configKey)) {
                    playerConfig.password = value;
                } else {
                    throw new ConfigurationException(configKey, "the given configKey '" + configKey + "' is unknown");
                }

            }

            connectAllPlayersAndMonitors();
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        Scheduler sched;
        try {
            sched = StdSchedulerFactory.getDefaultScheduler();
            JobDetail job = newJob(ReconnectJob.class).withIdentity("Reconnect", MPD_SCHEDULER_GROUP).build();
            CronTrigger trigger = newTrigger().withIdentity("Reconnect", MPD_SCHEDULER_GROUP)
                    .withSchedule(CronScheduleBuilder.cronSchedule("0 0 0 * * ?")).build();

            sched.scheduleJob(job, trigger);
            logger.debug("Scheduled a daily MPD Reconnect of all MPDs");
        } catch (SchedulerException se) {
            logger.warn("scheduling MPD Reconnect failed", se);
        }
    }

    /**
     * Delete all quartz scheduler jobs of the group <code>MPD</code>.
     */
    private void cancelScheduler() {
        try {
            Scheduler sched = StdSchedulerFactory.getDefaultScheduler();
            Set<JobKey> jobKeys = sched.getJobKeys(jobGroupEquals(MPD_SCHEDULER_GROUP));
            if (jobKeys.size() > 0) {
                sched.deleteJobs(new ArrayList<JobKey>(jobKeys));
                logger.debug("Found {} jobs to delete from DefaultScheduler (keys={})", jobKeys.size(), jobKeys);
            }
        } catch (SchedulerException e) {
            logger.warn("Couldn't remove job: {}", e.getMessage());
        }
    }

    /**
     * Connects to all configured {@link MPD}s
     */
    private void connectAllPlayersAndMonitors() {
        logger.debug("MPD binding connecting to players");
        for (String playerId : playerConfigCache.keySet()) {
            connect(playerId);
        }
    }

    /**
     * Connects the player <code>playerId</code> to the given <code>host</code>
     * and <code>port</code> and registers this binding as MPD-Event listener.
     *
     * @param playerId
     * @param host
     * @param port
     */
    private void connect(final String playerId) {
        MpdPlayerConfig config = null;
        try {
            logger.debug("connecting to " + playerId);

            config = playerConfigCache.get(playerId);
            if (config != null && config.instance == null) {
                MPD mpd = new MPD.Builder().server(config.host).port(config.port).password(config.password)
                        .timeout(CONNECTION_TIMEOUT).build();
                config.instance = mpd;

                MPDListener listener = new MPDListener(playerId, this);

                StandAloneMonitor mpdStandAloneMonitor = mpd.getMonitor();
                mpdStandAloneMonitor.addVolumeChangeListener(listener);
                mpdStandAloneMonitor.addPlayerChangeListener(listener);
                mpdStandAloneMonitor.addTrackPositionChangeListener(listener);
                mpdStandAloneMonitor.addOutputChangeListener(listener);
                mpdStandAloneMonitor.start();

                logger.debug("Connected to player '{}' with config {}", playerId, config);
            }
        } catch (UnknownHostException e) {
            logger.error("unknown host exception");
        } catch (MPDConnectionException ce) {
            logger.error("Error connecting to player '" + playerId + "' with config {}", config, ce);
        }
    }

    /**
     * Disconnects all available {@link MPD}s and their {@link StandAloneMonitor}-Thread.
     */
    private void disconnectPlayersAndMonitors() {
        for (String playerId : playerConfigCache.keySet()) {
            disconnect(playerId);
        }
    }

    /**
     * Disconnects the player <code>playerId</code>
     *
     * @param playerId the id of the player to disconnect from
     */
    private void disconnect(String playerId) {
        MpdPlayerConfig playerConfig = playerConfigCache.get(playerId);
        if (playerConfig != null) {
            MPD mpd = playerConfig.instance;
            if (mpd != null) {
                StandAloneMonitor monitor = mpd.getMonitor();
                if (monitor != null) {
                    monitor.stop();
                }
                mpd.close();
                playerConfig.instance = null;
            }
        }
    }

    /**
     * Reconnects to <code>playerId</code> that means disconnect first and try
     * to connect again.
     *
     * @param playerId the id of the player to disconnect from
     */
    private void reconnect(String playerId) {
        logger.info("reconnect player {}", playerId);
        disconnect(playerId);
        connect(playerId);
    }

    /**
     * Reconnects all MPDs and Monitors. Meaning disconnect first and try
     * to connect again.
     */
    private void reconnectAllPlayerAndMonitors() {
        disconnectPlayersAndMonitors();
        connectAllPlayersAndMonitors();
    }

    /**
     * Internal data structure which carries the connection details of one
     * MPD (there could be several)
     *
     * @author Thomas.Eichstaedt-Engelen
     */
    static class MpdPlayerConfig {

        String host;
        int port;
        String password;

        MPD instance;

        @Override
        public String toString() {
            return "MPD [host=" + host + ", port=" + port + ", password=" + password + "]";
        }

    }

    /**
     * A quartz scheduler job to simply do a reconnection to the MPDs.
     */
    public class ReconnectJob implements Job {
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            reconnectAllPlayerAndMonitors();
        }
    }

    /**
     * Internal class which stores the playerId and passes changes to the MpdBinding.
     *
     * @author Stefan Roellin
     */
    public class MPDListener implements VolumeChangeListener, PlayerBasicChangeListener, TrackPositionChangeListener,
            OutputChangeListener {

        private String playerId;
        private MpdBinding binding;

        public MPDListener(String playerId, MpdBinding binding) {
            this.playerId = playerId;
            this.binding = binding;
        }

        @Override
        public void volumeChanged(VolumeChangeEvent e) {
            binding.volumeChanged(playerId, e);
        }

        @Override
        public void playerBasicChange(PlayerBasicChangeEvent e) {
            binding.playerBasicChange(playerId, e);

        }

        @Override
        public void trackPositionChanged(TrackPositionChangeEvent e) {
            binding.trackPositionChanged(playerId, e);
        }

        @Override
        public void outputChanged(OutputChangeEvent e) {
            binding.outputChanged(playerId, e);
        }
    }

}
