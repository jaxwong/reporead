-- Stage 6: image files in each connection's latest complete snapshot, so Obsidian embeds (![[name.png]]) can be resolved
-- by name. Replaced with every published snapshot; removed with the connection.
create table repository_images (
    repository_connection_id bigint not null references repository_connections (id) on delete cascade,
    path                     text   not null,
    primary key (repository_connection_id, path)
);
