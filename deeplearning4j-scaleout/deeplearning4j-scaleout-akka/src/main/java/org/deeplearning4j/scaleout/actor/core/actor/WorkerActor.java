package org.deeplearning4j.scaleout.actor.core.actor;

import akka.actor.*;
import akka.actor.SupervisorStrategy.Directive;
import akka.cluster.Cluster;
import akka.cluster.ClusterEvent.MemberEvent;
import akka.contrib.pattern.DistributedPubSubExtension;
import akka.contrib.pattern.DistributedPubSubMediator;
import akka.contrib.pattern.DistributedPubSubMediator.Put;
import akka.japi.Function;
import org.deeplearning4j.scaleout.actor.core.protocol.Ack;
import org.deeplearning4j.scaleout.actor.core.protocol.ClearWorker;
import org.deeplearning4j.scaleout.actor.core.ClusterListener;
import org.deeplearning4j.scaleout.conf.Configuration;
import org.deeplearning4j.scaleout.conf.DeepLearningConfigurable;

import org.deeplearning4j.scaleout.job.Job;
import org.deeplearning4j.scaleout.perform.WorkerPerformer;
import org.deeplearning4j.scaleout.statetracker.StateTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.duration.Duration;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Iterative reduce actor for handling batch sizes
 * @author Adam Gibson
 *
 */
public class WorkerActor extends  UntypedActor implements DeepLearningConfigurable {



    protected Job currentJob;
    protected String id;
    Cluster cluster = Cluster.get(getContext().system());
    protected ActorRef clusterClient;
    protected String masterPath;
    protected StateTracker tracker;
    protected AtomicBoolean isWorking = new AtomicBoolean(false);
    protected Configuration conf;
    protected ActorRef mediator = DistributedPubSubExtension.get(getContext().system()).mediator();
    protected Cancellable heartbeat;
    protected static Logger log = LoggerFactory.getLogger(WorkerActor.class);
    protected WorkerPerformer workerPerformer;


    public WorkerActor(Configuration conf,StateTracker tracker,WorkerPerformer workerPerformer)throws Exception   {
        this(conf,null,tracker,workerPerformer);
    }


    public WorkerActor(Configuration conf,ActorRef client,StateTracker tracker,WorkerPerformer workerPerformer) throws Exception {
        this.tracker = tracker;
        this.workerPerformer = workerPerformer;
        //subscribe to broadcasts from workers (location agnostic)
        mediator.tell(new Put(getSelf()), getSelf());

        //subscribe to broadcasts from master (location agnostic)
        mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.BROADCAST, getSelf()), getSelf());
        //subscribe to shutdown messages
        mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.SHUTDOWN, getSelf()), getSelf());
        id = generateId();
        //replicate the network
        mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
                register()), getSelf());

        this.clusterClient = client;

        //ensure worker is available to tracker
        tracker.availableForWork(id);
        //master lookup
        masterPath = conf.get(MASTER_PATH,"");
        log.info("Registered with master " + id + " at master " + conf.get(MASTER_PATH));

        heartbeat();

        tracker.addWorker(id);


        setup(conf);
        //subscribe to broadcasts from workers (location agnostic)
        mediator.tell(new Put(getSelf()), getSelf());

        //subscribe to broadcasts from master (location agnostic)
        mediator.tell(new DistributedPubSubMediator.Subscribe(MasterActor.BROADCAST, getSelf()), getSelf());


        //subscribe to broadcasts from master (location agnostic)
        mediator.tell(new DistributedPubSubMediator.Subscribe(id, getSelf()), getSelf());

        heartbeat();

        tracker.addWorker(id);

    }





    public static Props propsFor(ActorRef actor,Configuration conf,StateTracker tracker) {
        return Props.create(WorkerActor.class,actor,conf,tracker);
    }

    public static Props propsFor(Configuration conf,StateTracker stateTracker) {
        return Props.create(WorkerActor.class,conf,stateTracker);
    }





    @SuppressWarnings("unchecked")
    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof DistributedPubSubMediator.SubscribeAck || message instanceof DistributedPubSubMediator.UnsubscribeAck) {
            DistributedPubSubMediator.SubscribeAck ack = (DistributedPubSubMediator.SubscribeAck) message;
            //reply
            mediator.tell(new DistributedPubSubMediator.Publish(ClusterListener.TOPICS,
                    message), getSelf());

            log.info("Subscribed to " + ack.toString());
        }


        else if(message instanceof Job) {
            Job job = (Job) message;
            workerPerformer.perform(job);
        }
        else if(message instanceof Ack) {
            log.info("Ack from master on worker " + id);
        }


        else
            unhandled(message);
    }


    @Override
    public void aroundPostStop() {
        super.aroundPostStop();
        //replicate the network
        mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
                new ClearWorker(id)), getSelf());
        heartbeat.cancel();
    }




    public  Job getcurrentJob() {
        try {
            if(currentJob == null)
                currentJob = (Job) tracker.getCurrent();
        }catch(Exception e) {
            throw new RuntimeException(e);
        }

        return currentJob;
    }




    protected void heartbeat() throws Exception {
        heartbeat = context().system().scheduler().schedule(Duration.apply(30, TimeUnit.SECONDS), Duration.apply(30, TimeUnit.SECONDS), new Runnable() {

            @Override
            public void run() {
                if(!tracker.isDone())
                    tracker.addWorker(id);

                if(!tracker.isDone() && tracker.needsReplicate(id)) {
                    try {
                        log.info("Updating worker " + id);
                        Job u = (Job) tracker.getCurrent();

                        if(u == null) {
                            return;
                        }

                        currentJob = u;
                        tracker.doneReplicating(id);
                    }catch(Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                //eventually consistent storage
                try {
                    checkJobAvailable();


                    if(currentJob != null && !isWorking.get() && tracker.jobFor(id) != null) {
                        log.info("Confirmation from " + currentJob.workerId() + " on work");
                        if(currentJob.getWork() == null)
                            throw new IllegalStateException("Work for worker " + id + " was null");

                        workerPerformer.perform(currentJob);
                        tracker.addUpdate(id, currentJob);
                        currentJob = null;

                    }

                    else if(currentJob == null || !isWorking.get() && tracker.jobFor(id) != null) {
                        if(tracker.jobFor(id) != null)
                            tracker.clearJob(id);
                        log.info("Clearing stale job... " + id);
                    }


                }catch(Exception e) {
                    throw new RuntimeException(e);
                }



            }

        }, context().dispatcher());

    }



    /**
     * Returns a worker state with the id generated by this worker
     * @return a worker state with the id of this worker
     */
    public WorkerState register() {
        return new WorkerState(this.id);
    }

    /**
     * Generates an id for this worker
     * @return a UUID for this worker
     */
    public String generateId() {
        String base = UUID.randomUUID().toString();
        String host = System.getProperty("akka.remote.netty.tcp.hostname","localhost");
        return host + "-" + base;
    }


    @Override
    public void postStop() throws Exception {
        super.postStop();
        try {
            tracker.removeWorker(id);

        }catch(Exception e) {
            log.info("Tracker already shut down");
        }
        log.info("Post stop on worker actor");
        cluster.unsubscribe(getSelf());
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        cluster.subscribe(getSelf(), MemberEvent.class);
        log.info("Pre start on worker");

    }

    protected void checkJobAvailable() throws Exception {
        Job j;

        if((j = tracker.jobFor(id)) == null || !tracker.workerEnabled(id)) {
            //inconsistent state
            if(!isWorking.get() && j != null)  {
                tracker.clearJob(id);
                log.info("Clearing stale job " + id);
            }

            return;
        }

        if(tracker.needsReplicate(id)) {
            try {
                log.info("Updating worker " + id);
                currentJob = (Job) tracker.getCurrent();
                tracker.doneReplicating(id);
            }catch(Exception e) {
                throw new RuntimeException(e);
            }
        }


        if(j != null && currentJob == null) {
            log.info("Assigning job for worker " + id);
            currentJob = j;
            //clear data, no point in keeping both in memory
            tracker.updateJob(new Job(id,null));

        }

    }



    @Override
    public void setup(Configuration conf) {
        this.conf = conf;
        String url = conf.get(MASTER_URL);
        this.masterPath = conf.get(MASTER_PATH);
        Address a = AddressFromURIString.apply(url);
        Cluster.get(context().system()).join(a);

        mediator = DistributedPubSubExtension.get(getContext().system()).mediator();

    }



    @Override
    public SupervisorStrategy supervisorStrategy() {
        return new OneForOneStrategy(0,
                Duration.Zero(),
                new Function<Throwable, Directive>() {
                    public Directive apply(Throwable cause) {
                        log.error("Problem with processing",cause);
                        mediator.tell(new DistributedPubSubMediator.Publish(MasterActor.MASTER,
                                new ClearWorker(id)), getSelf());


                        return SupervisorStrategy.restart();
                    }
                }
        );
    }










}