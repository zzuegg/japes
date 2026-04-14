package zzuegg.ecs.command;

import zzuegg.ecs.entity.Entity;
import zzuegg.ecs.world.World;

import java.util.List;

public final class CommandProcessor {

    private CommandProcessor() {}

    /**
     * Fast path: reads the flat buffer directly — no Command objects
     * are allocated. After processing, the buffer is reset so it can
     * be reused on the next tick.
     */
    public static void process(Commands cmds, World world) {
        int n = cmds.size();
        if (n == 0) return;

        int[] ops = cmds.ops();
        long[] ids = cmds.ids();
        Object[] refs = cmds.refs();

        int ii = 0;
        int ri = 0;

        for (int i = 0; i < n; i++) {
            switch (ops[i]) {
                case Commands.OP_SPAWN -> {
                    world.spawn((Record[]) refs[ri++]);
                    ii++;
                }
                case Commands.OP_DESPAWN -> {
                    world.despawnIfAlive(new Entity(ids[ii++]));
                }
                case Commands.OP_ADD -> {
                    var entity = new Entity(ids[ii++]);
                    if (world.isAlive(entity)) {
                        world.addComponent(entity, (Record) refs[ri++]);
                    } else { ri++; }
                }
                case Commands.OP_REMOVE -> {
                    var entity = new Entity(ids[ii++]);
                    if (world.isAlive(entity)) {
                        world.removeComponent(entity, asRecordClass(refs[ri++]));
                    } else { ri++; }
                }
                case Commands.OP_SET -> {
                    var entity = new Entity(ids[ii++]);
                    if (world.isAlive(entity)) {
                        world.setComponent(entity, (Record) refs[ri++]);
                    } else { ri++; }
                }
                case Commands.OP_INSERT_RES -> {
                    world.setResource(refs[ri++]);
                    ii++;
                }
                case Commands.OP_SET_RELATION -> {
                    var source = new Entity(ids[ii++]);
                    var target = new Entity(ids[ii++]);
                    if (world.isAlive(source) && world.isAlive(target)) {
                        world.setRelation(source, target, (Record) refs[ri++]);
                    } else { ri++; }
                }
                case Commands.OP_REMOVE_RELATION -> {
                    var source = new Entity(ids[ii++]);
                    var target = new Entity(ids[ii++]);
                    if (world.isAlive(source)) {
                        world.removeRelation(source, target, asRecordClass(refs[ri++]));
                    } else { ri++; }
                }
                default -> throw new IllegalStateException("Unknown command op: " + ops[i]);
            }
        }
        cmds.reset();
    }

    /**
     * Legacy path: processes a list of materialised Command objects.
     */
    public static void process(List<Commands.Command> commands, World world) {
        for (int i = 0, n = commands.size(); i < n; i++) {
            var command = commands.get(i);
            switch (command) {
                case Commands.SpawnCommand spawn -> world.spawn(spawn.components());
                case Commands.DespawnCommand despawn -> world.despawnIfAlive(despawn.entity());
                case Commands.AddCommand add -> {
                    if (world.isAlive(add.entity())) world.addComponent(add.entity(), add.component());
                }
                case Commands.RemoveCommand remove -> {
                    if (world.isAlive(remove.entity())) world.removeComponent(remove.entity(), remove.type());
                }
                case Commands.SetCommand set -> {
                    if (world.isAlive(set.entity())) world.setComponent(set.entity(), set.component());
                }
                case Commands.InsertResourceCommand res -> world.setResource(res.resource());
                case Commands.SetRelationCommand rel -> {
                    if (world.isAlive(rel.source()) && world.isAlive(rel.target()))
                        world.setRelation(rel.source(), rel.target(), rel.value());
                }
                case Commands.RemoveRelationCommand rel -> {
                    if (world.isAlive(rel.source()))
                        world.removeRelation(rel.source(), rel.target(), rel.type());
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Record> asRecordClass(Object o) { return (Class<? extends Record>) o; }
}
