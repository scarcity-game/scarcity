package com.pessimisticit.scarcity.resourcemodel;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pessimisticit.scarcity.world.model.Ids;
import com.pessimisticit.scarcity.world.model.Resource;
import com.pessimisticit.scarcity.world.model.ResourceKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

class ResourceControllerTest extends AbstractDbTest {

    @Autowired
    MockMvc mvc;

    @Test
    void listScopedToAGameCombinesWithFiltersAndPaging() throws Exception {
        String g1 = create(resource(ResourceKind.GAME, 1), null);
        String g2 = create(resource(ResourceKind.GAME, 2), null);
        String a1 = create(resource(ResourceKind.AREA, 1), g1);
        String a2 = create(resource(ResourceKind.AREA, 2), g1);
        String b1 = create(resource(ResourceKind.AREA, 3), g2);
        String tag = unique();
        String e1 = create(tagged(tag, 1), a1);
        String e2 = create(tagged(tag, 2), a2);
        create(tagged(tag, 3), b1);
        mvc.perform(get("/resources").param("type", "entity").param("game", g1).param("name", tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(e1))
                .andExpect(jsonPath("$.items[1].id").value(e2));
        mvc.perform(get("/resources").param("type", "entity").param("game", g1).param("name", tag)
                .param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(e2));
        mvc.perform(get("/resources").param("type", "entity").param("game", g1).param("name", tag).param("hp", "1.0"))
                .andExpect(jsonPath("$.total").value(1));
        // unknown game: empty page; malformed or wrong-kind id: 400 with the error body
        mvc.perform(get("/resources").param("type", "entity").param("game", Ids.newId(ResourceKind.GAME)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/resources").param("type", "entity").param("game", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/resources").param("type", "entity").param("game", a1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/resources").param("type", "entity").param("game", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOneWithEtagAnd304() throws Exception {
        String game = create(resource(ResourceKind.GAME, "g"), null);
        String area = create(resource(ResourceKind.AREA, 10), game);
        mvc.perform(get("/resources/" + game))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.id").value(game))
                .andExpect(jsonPath("$.resourceKind").value("game"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.children[0]").value(area))
                .andExpect(jsonPath("$.name").value("g"))
                .andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/resources/" + game).header(HttpHeaders.IF_NONE_MATCH, "\"2\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(content().string(""));
        mvc.perform(get("/resources/" + game).header(HttpHeaders.IF_NONE_MATCH, "\"1\""))
                .andExpect(status().isOk());
    }

    @Test
    void notFoundAndMalformedHaveErrorBody() throws Exception {
        mvc.perform(get("/resources/" + Ids.newId(ResourceKind.ENTITY)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/resources/not-an-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isString());
        mvc.perform(get("/resources/area:nope/children"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/resources/" + Ids.newId(ResourceKind.AREA) + "/children"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isString());
    }

    @Test
    void listRequiresTypeAndFiltersAndPages() throws Exception {
        String tag = unique();
        for (int i = 0; i < 3; i++) {
            create(tagged(tag, i), null);
        }
        create(tagged("other", 0), null);
        mvc.perform(get("/resources")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("type is required"));
        mvc.perform(get("/resources").param("type", "zone")).andExpect(status().isBadRequest());
        mvc.perform(get("/resources").param("type", "entity").param("name", tag).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].hp").value(0.0));
        mvc.perform(get("/resources").param("type", "entity").param("name", tag).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].hp").value(2.0));
        mvc.perform(get("/resources").param("type", "entity").param("name", tag).param("hp", "1.0"))
                .andExpect(jsonPath("$.total").value(1));
        // defaults and clamping
        mvc.perform(get("/resources").param("type", "entity").param("name", tag))
                .andExpect(jsonPath("$.size").value(50));
        mvc.perform(get("/resources").param("type", "entity").param("name", tag).param("size", "100000"))
                .andExpect(jsonPath("$.size").value(200));
        mvc.perform(get("/resources").param("type", "entity").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/resources").param("type", "entity").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/resources").param("type", "entity").param("page", "x")).andExpect(status().isBadRequest());
        mvc.perform(get("/resources").param("type", "entity").param("bad key!", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void listWithMaxFilters() throws Exception {
        // Test with exactly 16 filter parameters - should succeed
        StringBuilder url = new StringBuilder("/resources?type=game");
        for (int i = 1; i <= 16; i++) {
            url.append("&k").append(i).append("=v").append(i);
        }
        mvc.perform(get(url.toString()))
                .andExpect(status().isOk());

        // Test with 17 filter parameters - should fail with 400
        url = new StringBuilder("/resources?type=game");
        for (int i = 1; i <= 17; i++) {
            url.append("&k").append(i).append("=v").append(i);
        }
        mvc.perform(get(url.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("at most 16 filter parameters"));
    }

    @Test
    void childrenWithOptionalTypeAndPaging() throws Exception {
        String game = create(resource(ResourceKind.GAME, 1), null);
        String area = create(resource(ResourceKind.AREA, 1), game);
        String e1 = create(resource(ResourceKind.ENTITY, 1), area);
        String e2 = create(resource(ResourceKind.ENTITY, 2), area);
        mvc.perform(get("/resources/" + area + "/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value(e1))
                .andExpect(jsonPath("$.items[1].id").value(e2));
        mvc.perform(get("/resources/" + area + "/children").param("type", "entity").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(e2))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/resources/" + area + "/children").param("type", "area"))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
